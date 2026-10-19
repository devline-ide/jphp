package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.Modifier;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ClassDumper;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.Assert.*;

/** Declaration data only: no module include, class definition, or loader.defineClass. */
public class PhpDeferredClassRelationDumperTest extends JvmCompilerCase {
    private static final Context CONTEXT = new Context("<?php /* relation codec */");

    private static final class LookupTrap extends Environment {
        boolean armed;
        int lookups;
        LookupTrap(CompileScope scope) { super(scope, new ByteArrayOutputStream()); }
        @Override public ClassEntity fetchClass(String name, boolean autoload) {
            if (armed) { lookups++; throw new AssertionError("Unexpected class lookup: " + name); }
            return super.fetchClass(name, autoload);
        }
        @Override public ClassEntity fetchClass(String name, boolean autoload, PhpLanguageProfile profile) {
            if (armed) { lookups++; throw new AssertionError("Unexpected class lookup: " + name); }
            return super.fetchClass(name, autoload, profile);
        }
    }

    private LookupTrap environment(boolean debug) {
        var scope = newScope();
        scope.setDebugMode(debug);
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        return new LookupTrap(scope);
    }

    private static TraceInfo trace(int line) { return new TraceInfo("Relations.php", line, line + 1); }
    private static PhpClassRelations.Name name(String name, int line) {
        return new PhpClassRelations.Name(name, trace(line));
    }

    private static PhpClassRelations recipe() {
        return new PhpClassRelations(name("Missing\\ParentCase", 2), List.of(
                new PhpClassRelations.InterfaceUse(name("Missing\\FirstI", 3), PhpClassRelations.InterfaceKind.IMPLEMENTS),
                new PhpClassRelations.InterfaceUse(name("Missing\\SecondI", 4), PhpClassRelations.InterfaceKind.EXTENDS)), List.of(
                new PhpClassRelations.TraitGroup(List.of(name("Missing\\T2", 5), name("Missing\\T1", 6)), List.of(
                        new PhpClassRelations.Alias(null, "plain", "AliasCase", Modifier.PROTECTED, true, trace(7)),
                        new PhpClassRelations.Precedence("Missing\\T2", "MiXeD", List.of(name("Missing\\T1", 9), name("Missing\\T3", 10)), trace(8)),
                        new PhpClassRelations.Alias("Missing\\T2", "MiXeD", null, Modifier.PRIVATE, false, trace(11))), trace(5)),
                new PhpClassRelations.TraitGroup(List.of(name("Missing\\T4", 12)), List.of(), trace(12))));
    }

    private static ClassEntity fixture(boolean members) {
        var entity = new ClassEntity(CONTEXT);
        entity.setId(1); entity.setName("ColdCodec"); entity.setInternalName("ColdCodec$1");
        entity.setStatic(false); entity.setDeclarationId(0);
        entity.setDeferredParentName("Missing\\ParentCase"); entity.setDeclarationRelations(recipe());
        // The isolated dumper test never defines these bytes; the module test below uses real compiler output.
        entity.setData(new byte[]{1, 2, 3, 4});
        if (members) {
            entity.addConstant(new ConstantEntity("OWN", LongMemory.valueOf(9), true));
            var property = new PropertyEntity(CONTEXT);
            property.setName("ownValue"); property.setDefaultValue(LongMemory.valueOf(7));
            entity.addProperty(property);
            var method = new MethodEntity(CONTEXT);
            method.setName("ownMethod"); method.setModifier(Modifier.PUBLIC); method.setClazz(entity);
            entity.addMethod(method, null);
        }
        return entity;
    }

    private static byte[] save(ClassDumper dumper, ClassEntity entity) throws IOException {
        var out = new ByteArrayOutputStream(); dumper.save(entity, out); return out.toByteArray();
    }
    private static ClassEntity load(ClassDumper dumper, byte[] bytes) throws IOException {
        return dumper.load(new ByteArrayInputStream(bytes));
    }
    private static void cold(ClassEntity entity) {
        assertTrue(entity.isDeclarationTemplate()); assertEquals(0, entity.getDeclarationId());
        assertNull(entity.getParent()); assertNull(entity.getNativeClass());
        assertTrue(entity.getInterfaces().isEmpty()); assertTrue(entity.getTraits().isEmpty());
    }

    @Test public void compiledColdCatalogRoundTripsWithoutNameLookup() throws Throwable {
        String php = "function declareCold(){class ColdCodec extends MissingParent implements MissingI1,MissingI2 {"
                + "use MissingT1,MissingT2 {MissingT2::MiXeD insteadof MissingT1; MissingT2::MiXeD as protected AliasCase; plain as final Keep;}"
                + "const OWN=9; public int $ownValue=7; function ownMethod(){return 9;}}}";
        for (boolean debug : new boolean[]{false, true}) for (boolean strict : new boolean[]{false, true}) {
            var env = environment(debug);
            try {
                var context = new Context((strict ? "declare(strict_types=1); " : "") + php);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                assertEquals(1, module.getDeferredClasses().size());
                var original = module.findDeferredClass(0); cold(original);
                assertEquals(3, original.getDeclarationRelations().traitGroups().get(0).adaptations().size());
                var out = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
                dumper.save(module, out);
                env.armed = true;
                var copy = dumper.load(new ByteArrayInputStream(out.toByteArray()));
                assertEquals(1, copy.getDeferredClasses().size());
                assertTrue(copy.getClasses().isEmpty());
                var restored = copy.findDeferredClass(0); cold(restored);
                assertRelations(original.getDeclarationRelations(), restored.getDeclarationRelations(), true);
                assertEquals("MissingParent", restored.getDeclarationRelations().parent().name());
                assertEquals("MissingI1", restored.getDeclarationRelations().interfaces().get(0).name().name());
                assertEquals("MissingI2", restored.getDeclarationRelations().interfaces().get(1).name().name());
                var aliases = restored.getDeclarationRelations().traitGroups().get(0).adaptations();
                assertEquals("AliasCase", ((PhpClassRelations.Alias) aliases.get(1)).alias());
                var unqualified = (PhpClassRelations.Alias) aliases.get(2);
                assertNull(unqualified.trait()); assertTrue(unqualified.makeFinal());
                assertEquals("Keep", unqualified.alias());
                assertNotNull(restored.findConstant("OWN")); assertNotNull(restored.findProperty("ownValue"));
                assertNotNull(restored.findMethod("ownmethod")); assertEquals(0, env.lookups);
            } finally { env.armed = false; try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
        }
    }

    @Test public void orderedRecipeAndOwnMembersPreserveDebugTraces() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) {
            var env = environment(debug);
            try {
                var dumper = new ClassDumper(CONTEXT, null, env, debug);
                var bytes = save(dumper, fixture(true)); env.armed = true;
                var copy = load(dumper, bytes); cold(copy);
                assertNotNull(copy.findConstant("OWN")); assertNotNull(copy.findProperty("ownValue"));
                assertNotNull(copy.findMethod("ownmethod")); assertEquals(1, copy.getOwnedMethods().size());
                assertRelations(recipe(), copy.getDeclarationRelations(), debug);
                assertArrayEquals(fixture(true).getData(), copy.getData());
                assertRelations(recipe(), load(dumper, save(dumper, copy)).getDeclarationRelations(), debug);
                assertEquals(0, env.lookups);
            } finally { env.armed = false; try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
        }
    }

    private static void assertTrace(TraceInfo expected, TraceInfo actual, boolean debug) {
        if (debug) {
            assertEquals(expected.getFileName(), actual.getFileName());
            assertEquals(expected.getStartLine(), actual.getStartLine());
            assertEquals(expected.getStartPosition(), actual.getStartPosition());
        } else {
            assertEquals(new TraceInfo(CONTEXT).getFileName(), actual.getFileName());
            assertEquals(0, actual.getStartLine()); assertEquals(0, actual.getStartPosition());
        }
    }
    private static void assertName(PhpClassRelations.Name a, PhpClassRelations.Name b, boolean debug) {
        assertEquals(a.name(), b.name()); assertTrace(a.trace(), b.trace(), debug);
    }
    private static void assertRelations(PhpClassRelations a, PhpClassRelations b, boolean debug) {
        assertName(a.parent(), b.parent(), debug); assertEquals(a.interfaces().size(), b.interfaces().size());
        for (int i = 0; i < a.interfaces().size(); i++) {
            assertEquals(a.interfaces().get(i).kind(), b.interfaces().get(i).kind());
            assertName(a.interfaces().get(i).name(), b.interfaces().get(i).name(), debug);
        }
        assertEquals(a.traitGroups().size(), b.traitGroups().size());
        for (int i = 0; i < a.traitGroups().size(); i++) {
            var x = a.traitGroups().get(i); var y = b.traitGroups().get(i); assertTrace(x.trace(), y.trace(), debug);
            assertEquals(x.traits().size(), y.traits().size());
            for (int j = 0; j < x.traits().size(); j++) assertName(x.traits().get(j), y.traits().get(j), debug);
            assertEquals(x.adaptations().size(), y.adaptations().size());
            for (int j = 0; j < x.adaptations().size(); j++) {
                var p = x.adaptations().get(j); var q = y.adaptations().get(j); assertTrace(p.trace(), q.trace(), debug);
                if (p instanceof PhpClassRelations.Alias alias) {
                    assertTrue(q instanceof PhpClassRelations.Alias); var other = (PhpClassRelations.Alias) q;
                    assertEquals(alias.trait(), other.trait()); assertEquals(alias.method(), other.method());
                    assertEquals(alias.alias(), other.alias()); assertEquals(alias.visibility(), other.visibility());
                    assertEquals(alias.makeFinal(), other.makeFinal());
                } else {
                    assertTrue(q instanceof PhpClassRelations.Precedence); var first = (PhpClassRelations.Precedence) p;
                    var other = (PhpClassRelations.Precedence) q;
                    assertEquals(first.preferredTrait(), other.preferredTrait()); assertEquals(first.method(), other.method());
                    assertEquals(first.excluded().size(), other.excluded().size());
                    for (int k = 0; k < first.excluded().size(); k++) assertName(first.excluded().get(k), other.excluded().get(k), debug);
                }
            }
        }
    }

    /** Walks the saved v7 footer using actual modified-UTF fields, retaining mutation offsets. */
    private static final class Footer {
        final byte[] bytes; final int start;
        final Map<String, Integer> at = new LinkedHashMap<>();
        final Map<String, Integer> end = new HashMap<>();
        final List<String> counts = new ArrayList<>(), required = new ArrayList<>(), optional = new ArrayList<>();
        final List<String> interfaceKinds = new ArrayList<>(), adaptationKinds = new ArrayList<>(), visibilities = new ArrayList<>();
        final ByteArrayInputStream raw; final DataInputStream in;
        Footer(byte[] bytes) throws IOException {
            this.bytes = bytes; int found = -1;
            for (int i = 0; i + 8 <= bytes.length; i++)
                if (intAt(bytes, i) == bytes.length - i - 4 && intAt(bytes, i + 4) == 7) {
                    assertEquals("Unique actual metadata footer", -1, found); found = i;
                }
            assertTrue("Saved ClassDumper footer", found >= 0); start = found;
            raw = new ByteArrayInputStream(bytes, start + 4, bytes.length - start - 4); in = new DataInputStream(raw);
            integer("version"); in.readBoolean(); in.readBoolean(); in.readBoolean(); integer("ordinal");
            if (in.readBoolean()) namedTrace("parent");
            int interfaces = count("interfaces");
            for (int i = 0; i < interfaces; i++) { octet("interfaceKind" + i, interfaceKinds); namedTrace("interface" + i); }
            int groups = count("groups");
            for (int i = 0; i < groups; i++) {
                trace(); int traits = count("traits" + i);
                for (int j = 0; j < traits; j++) namedTrace("trait" + i + ":" + j);
                int adaptations = count("adaptations" + i);
                for (int j = 0; j < adaptations; j++) {
                    String id = i + ":" + j; int kind = octet("adaptationKind" + id, adaptationKinds); trace();
                    if (kind == 1) {
                        name("aliasTrait" + id, true); name("method" + id, false); name("alias" + id, true);
                        octet("visibility" + id, visibilities); in.readBoolean();
                    } else {
                        assertEquals(2, kind); name("preferred" + id, false); name("method" + id, false);
                        int exclusions = count("exclusions" + id);
                        for (int k = 0; k < exclusions; k++) namedTrace("excluded" + id + ":" + k);
                    }
                }
            }
            assertEquals(0, integer("checks")); assertEquals(0, raw.available());
        }
        int position() { return bytes.length - raw.available(); }
        int integer(String field) throws IOException { at.put(field, position()); return in.readInt(); }
        int count(String field) throws IOException { counts.add(field); return integer(field); }
        int octet(String field, List<String> group) throws IOException { group.add(field); at.put(field, position()); return in.readUnsignedByte(); }
        void trace() throws IOException { if (in.readBoolean()) { in.readInt(); in.readInt(); in.readUTF(); } }
        void name(String field, boolean nullable) throws IOException {
            (nullable ? optional : required).add(field); at.put(field, position());
            int length = in.readInt(); if (length >= 0) in.readUTF(); end.put(field, position());
        }
        void namedTrace(String field) throws IOException { name(field, false); trace(); }
        byte[] number(String field, int value) { var copy = bytes.clone(); ByteBuffer.wrap(copy).putInt(at.get(field), value); return copy; }
        byte[] octet(String field, int value) { var copy = bytes.clone(); copy[at.get(field)] = (byte) value; return copy; }
        byte[] name(String field, String value) throws IOException {
            var out = new ByteArrayOutputStream(); var encoded = new DumpOutputStream(out);
            encoded.writeName(value);
            return splice(bytes, at.get(field), end.get(field), out.toByteArray(), start);
        }
    }
    private static int intAt(byte[] bytes, int at) { return ByteBuffer.wrap(bytes).getInt(at); }
    private static byte[] splice(byte[] bytes, int from, int to, byte[] replacement, int footer) {
        var out = new ByteArrayOutputStream(); out.write(bytes, 0, from); out.write(replacement, 0, replacement.length);
        out.write(bytes, to, bytes.length - to); var result = out.toByteArray();
        ByteBuffer.wrap(result).putInt(footer, result.length - footer - 4); return result;
    }
    private static void rejects(ClassDumper dumper, byte[] bytes, String message) throws IOException {
        try { load(dumper, bytes); fail("Accepted malformed metadata: " + message); }
        catch (DumpException e) { assertTrue(e.getMessage(), e.getMessage().contains(message)); }
    }

    @Test public void malformedKindsNamesAndVisibilityRejectBeforeLookup() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) {
            var env = environment(debug);
            try {
                var dumper = new ClassDumper(CONTEXT, null, env, debug); var bytes = save(dumper, fixture(false));
                var footer = new Footer(bytes); env.armed = true; cold(load(dumper, bytes));
                for (String field : footer.interfaceKinds) for (int kind : new int[]{0, 3, 255})
                    rejects(dumper, footer.octet(field, kind), "interface kind");
                for (String field : footer.adaptationKinds) for (int kind : new int[]{0, 3, 255})
                    rejects(dumper, footer.octet(field, kind), "adaptation kind");
                for (String field : footer.visibilities) for (int visibility : new int[]{4, 255})
                    rejects(dumper, footer.octet(field, visibility), "visibility");
                for (String field : footer.required) for (String invalid : Arrays.asList(null, "", "bad\0name"))
                    rejects(dumper, footer.name(field, invalid), "relation name");
                for (String field : footer.optional) for (String invalid : new String[]{"", "bad\0name"})
                    rejects(dumper, footer.name(field, invalid), "relation name");
                assertEquals(0, env.lookups);
            } finally { env.armed = false; try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
        }
    }

    @Test public void invalidCountsBudgetOrdinalAndTrailingMetadataRejectBeforeLookup() throws Throwable {
        var env = environment(true);
        try {
            var dumper = new ClassDumper(CONTEXT, null, env, true); var bytes = save(dumper, fixture(false));
            var footer = new Footer(bytes); env.armed = true; cold(load(dumper, bytes));
            for (String field : footer.counts) for (int count : new int[]{-1, 65536, Integer.MAX_VALUE})
                rejects(dumper, footer.number(field, count), "relation count");
            for (String field : List.of("traits0", "traits1", "exclusions0:1"))
                rejects(dumper, footer.number(field, 0), "relation count");
            // Interfaces consume two budget units: group count 65535 must fail immediately, not allocate/read 65535 groups.
            rejects(dumper, footer.number("groups", 65535), "relation count");
            rejects(dumper, footer.number("ordinal", -2), "declaration id");
            rejects(dumper, footer.number("ordinal", -1), "require a deferred");
            rejects(dumper, footer.name("parent", "DifferentParent"), "parent recipe");
            rejects(dumper, splice(bytes, bytes.length, bytes.length, new byte[]{42}, footer.start), "metadata size");
            assertEquals(0, env.lookups);
        } finally { env.armed = false; try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void coldComposedTablesRejectBeforeAnyInterfaceOrTraitFetch() throws Throwable {
        var env = environment(false);
        try {
            var dumper = new ClassDumper(CONTEXT, null, env, false); var bytes = save(dumper, fixture(false));
            var cursor = new ByteArrayInputStream(bytes); var in = new DataInputStream(cursor);
            in.readUTF(); in.readBoolean(); in.readInt(); in.readBoolean(); in.readBoolean();
            skipName(in); skipName(in); skipTrace(in); skipName(in);
            for (int i = 0; i < 4; i++) assertEquals(0, in.readInt()); // constants, properties, methodCounts, ownedMethods
            int interfacesAt = bytes.length - cursor.available(); assertEquals(0, in.readInt());
            int traitsAt = bytes.length - cursor.available(); assertEquals(0, in.readInt());
            env.armed = true; cold(load(dumper, bytes));
            for (int at : new int[]{interfacesAt, traitsAt}) {
                var out = new ByteArrayOutputStream(); var table = new DumpOutputStream(out);
                table.writeInt(1); table.writeName("MissingComposedRelation");
                // Splice a prefix table; metadata's own raw-length remains unchanged.
                var merged = new ByteArrayOutputStream(); merged.write(bytes, 0, at); merged.write(out.toByteArray());
                merged.write(bytes, at + 4, bytes.length - at - 4);
                rejects(dumper, merged.toByteArray(), "Cold declaration contains composed relations");
            }
            assertEquals(0, env.lookups);
        } finally { env.armed = false; try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }
    private static void skipName(DataInputStream in) throws IOException { if (in.readInt() >= 0) in.readUTF(); }
    private static void skipTrace(DataInputStream in) throws IOException { if (in.readBoolean()) { in.readInt(); in.readInt(); in.readUTF(); } }
}
