package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpClassNames;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.StandaloneLoader;
import php.runtime.loader.dump.*;
import php.runtime.reflection.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Native PHP 8.5.11 class identity is ASCII-insensitive and preserves non-ASCII bytes. */
public class PhpUnicodeClassNamesTest extends JvmCompilerCase {
    private ModuleEntity compile(Environment env, String code, PhpLanguageProfile profile, boolean dump) throws Throwable {
        Context context = new Context(code); context.setPhpLanguageProfile(profile);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dump) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        return module;
    }
    private Memory include(Environment env, ModuleEntity module) throws Throwable {
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        return module.include(env);
    }
    private ClassEntity symbol(String name, PhpLanguageProfile profile) {
        ModuleEntity module = new ModuleEntity(new Context("")); module.setPhpLanguageProfile(profile);
        ClassEntity entity = new ClassEntity(module.getContext()); entity.setModule(module); entity.setName(name);
        return entity;
    }

    @Test public void testNativeClassNamesSourceAndDump() throws Throwable {
        File file = new File(Thread.currentThread().getContextClassLoader().getResource("resources/type_hinting/php85_unicode_class_names.php").toURI());
        String expected = Files.readString(file.toPath().resolveSibling("php85_unicode_class_names.expected"), StandardCharsets.UTF_8);
        for (boolean dump : new boolean[] {false, true}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); Environment env = new Environment(newScope(), output);
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                Context context = new Context(file); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                ModuleEntity module = new JvmCompiler(env, context).compile(false);
                if (dump) { var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
                    dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray())); }
                include(env, module); env.getDefaultBuffer().flush();
                assertEquals("dump=" + dump, expected, output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
                assertEquals("UCN\\Éclair", env.fetchClass("UCN\\ÉCLAIR", false, PhpLanguageProfile.PHP_8_5).getName());
                assertEquals("UCN\\éclair", env.fetchClass("UCN\\éCLAIR", false, PhpLanguageProfile.PHP_8_5).getName());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testMixedProfilesDeclarationOrderAndLookup() throws Throwable {
        for (boolean dump : new boolean[] {false, true}) for (boolean modernFirst : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            try {
                String modern = "class Mixedé {} return 1;", legacy = "class MixedÉ {} return 1;";
                include(env, compile(env, modernFirst ? modern : legacy,
                        modernFirst ? PhpLanguageProfile.PHP_8_5 : PhpLanguageProfile.JPHP_LEGACY, dump));
                include(env, compile(env, modernFirst ? legacy : modern,
                        modernFirst ? PhpLanguageProfile.JPHP_LEGACY : PhpLanguageProfile.PHP_8_5, dump));
                assertEquals("MixedÉ", env.fetchClass("MixedÉ", false, PhpLanguageProfile.PHP_8_5).getName());
                assertEquals("Mixedé", env.fetchClass("Mixedé", false, PhpLanguageProfile.PHP_8_5).getName());
                assertEquals("Mixedé", env.fetchClass("Mixedé", false, PhpLanguageProfile.JPHP_LEGACY).getName());
                assertNull(env.fetchClass("MixedÊ", false, PhpLanguageProfile.PHP_8_5));
                assertEquals("MixedÉ", include(env, compile(env,
                        "class MixedChild extends MixedÉ {} return get_parent_class(new MixedChild);",
                        PhpLanguageProfile.PHP_8_5, dump)).toString());
                assertEquals("Mixedé|MixedÉ", include(env, compile(env,
                        "return get_class(new Mixedé).'|'.get_class(new MixedÉ);", PhpLanguageProfile.PHP_8_5, dump)).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testJavaAliasesAtomicRegistrationAndCopiedScopes() throws Throwable {
        CompileScope scope = newScope(); ClassEntity upper = symbol("AliasÉ", PhpLanguageProfile.PHP_8_5);
        ClassEntity lower = symbol("Aliasé", PhpLanguageProfile.PHP_8_5);
        scope.registerClass("AliasÉ", upper); scope.registerClass("Aliasé", lower);
        scope.registerClass("UpperAlias", upper);
        assertSame(upper, scope.fetchUserClass("UpperAlias", "wrong-hint", true));
        assertSame(lower, scope.fetchUserClass("Aliasé", "aliasé", true));
        scope.registerClass("UpperAlias", lower); assertSame(lower, scope.fetchUserClass("UpperAlias", "upperalias", true));
        scope.registerClass("UpperAlias", upper); assertSame(upper, scope.fetchUserClass("UpperAlias", "upperalias", true));
        ClassEntity oldAliasA = symbol("OldAliasA", PhpLanguageProfile.JPHP_LEGACY), oldAliasB = symbol("OldAliasB", PhpLanguageProfile.JPHP_LEGACY);
        scope.registerClass("LegacyAlias", oldAliasA); scope.registerClass("LegacyAlias", oldAliasB);
        assertSame(oldAliasB, scope.fetchUserClass("LegacyAlias", "legacyalias", false));
        ModuleEntity collision = new ModuleEntity(new Context("")); collision.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        ClassEntity first = symbol("Unpublished", PhpLanguageProfile.PHP_8_5); first.setStatic(true);
        ClassEntity second = symbol("AliasÉ", PhpLanguageProfile.PHP_8_5); second.setStatic(true);
        collision.addClass(first); collision.addClass(second);
        try { scope.registerModule(collision); fail("Class batch collision accepted"); } catch (php.runtime.exceptions.CriticalException expected) { }
        assertFalse(scope.getClassMap().containsKey("unpublished")); assertSame(upper, scope.fetchUserClass("AliasÉ", "aliasé", true));
        ClassEntity old = symbol("LegacyÉ", PhpLanguageProfile.JPHP_LEGACY); scope.registerClass(old);
        assertSame(old, scope.fetchUserClass("Legacyé", "legacyé", false));
        assertNull(scope.fetchUserClass("Legacyé", "legacyé", true));
        CompileScope copied = new CompileScope(scope);
        assertSame(old, copied.fetchUserClass("Legacyé", "legacyé", false));
        assertSame(lower, copied.fetchUserClass("Aliasé", "aliasé", true));
        assertTrue(scope.getClassMap().containsKey("aliasÉ")); assertTrue(scope.getClassMap().containsKey("aliasé"));
        Environment parent = new Environment(scope), child = null;
        try {
            assertSame(upper, parent.fetchClass("UpperAlias", false, PhpLanguageProfile.PHP_8_5));
            scope.registerClass("UpperAlias", lower); assertSame(lower, parent.fetchClass("UpperAlias", false, PhpLanguageProfile.PHP_8_5));
            scope.registerClass("UpperAlias", upper); assertSame(upper, parent.fetchClass("UpperAlias", false, PhpLanguageProfile.PHP_8_5));
            assertSame(old, parent.fetchClass("Legacyé", false, PhpLanguageProfile.JPHP_LEGACY));
            assertFalse(parent.getLoadedClasses().containsKey("legacyé"));
            assertNull(parent.fetchClass("Legacyé", false, PhpLanguageProfile.PHP_8_5));
            assertSame(old, parent.fetchClass("LegacyÉ", false, PhpLanguageProfile.PHP_8_5));
            child = new Environment(parent);
            assertSame(upper, child.fetchClass("UpperAlias", false, PhpLanguageProfile.PHP_8_5));
            assertNull(child.fetchClass("Legacyé", false, PhpLanguageProfile.PHP_8_5));
            ModuleEntity replacement = new ModuleEntity(new Context("")); replacement.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ClassEntity modernReplacement = symbol("LegacyÉ", PhpLanguageProfile.PHP_8_5); modernReplacement.setStatic(true);
            replacement.addClass(modernReplacement); parent.registerModule(replacement, true);
            assertSame(modernReplacement, parent.fetchClass("LegacyÉ", false, PhpLanguageProfile.PHP_8_5));
            assertNull(parent.fetchClass("Legacyé", false, PhpLanguageProfile.JPHP_LEGACY));
            assertSame(old, child.fetchClass("Legacyé", false, PhpLanguageProfile.JPHP_LEGACY));
        } finally { if (child != null) child.doFinal(); parent.doFinal(); }
    }

    @Test public void testTurkishLocaleModernClassAndLazyNativeCache() throws Throwable {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("iterator", PhpClassNames.canonicalKey("Iterator"));
            assertTrue(PhpClassNames.equals("I", "ı", PhpLanguageProfile.JPHP_LEGACY));
            assertFalse(PhpClassNames.equals("I", "ı", PhpLanguageProfile.PHP_8_5));
            assertTrue(PhpClassNames.equals("Σ", "ς", PhpLanguageProfile.JPHP_LEGACY));
            assertFalse(PhpClassNames.equals("Σ", "ς", PhpLanguageProfile.PHP_8_5));
            assertEquals("ı", PhpClassNames.key("I", PhpLanguageProfile.JPHP_LEGACY));
            for (boolean dump : new boolean[] {false, true}) {
                Environment env = new Environment(newScope());
                try {
                    assertEquals("UCNIterator", include(env, compile(env,
                            "class UCNIterator {} return get_class(new ucniterator);", PhpLanguageProfile.PHP_8_5, dump)).toString());
                    assertNotNull(env.fetchClass("ITERATOR", false, PhpLanguageProfile.PHP_8_5));
                    assertNotNull(env.fetchClass("iterator", false, PhpLanguageProfile.PHP_8_5));
                    assertSame(env.fetchClass("ITERATOR", false, PhpLanguageProfile.PHP_8_5),
                            env.fetchClass("iterator", false, PhpLanguageProfile.PHP_8_5));
                } finally { env.doFinal(); }
            }
        } finally { Locale.setDefault(previous); }
    }

    @php.runtime.annotation.Reflection.Name("NativeÉ")
    public static class NativeUpper extends php.runtime.lang.BaseObject {
        public NativeUpper(Environment env, ClassEntity clazz) { super(env, clazz); }
    }
    @php.runtime.annotation.Reflection.Name("Nativeé")
    public static class NativeLower extends php.runtime.lang.BaseObject {
        public NativeLower(Environment env, ClassEntity clazz) { super(env, clazz); }
    }
    @php.runtime.annotation.Reflection.Name("NativeHost")
    public static class NativeHost extends php.runtime.lang.BaseObject {
        public NativeHost(Environment env, ClassEntity clazz) { super(env, clazz); }
    }

    @Test public void testNativeUnicodeDeclarationsAliasesAndGuards() throws Throwable {
        for (boolean dump : new boolean[] {false, true}) for (boolean legacyFirst : new boolean[] {false, true}) {
            CompileScope scope = newScope(); scope.registerLazyClass(null, NativeUpper.class); scope.registerLazyClass(null, NativeLower.class);
            Environment env = new Environment(scope);
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                ClassEntity legacySelected = null;
                if (legacyFirst) {
                    // The old folded lazy index keeps the last registration, NativeLower.
                    legacySelected = scope.fetchUserClass("NativeÉ", "nativeé", false);
                    assertEquals("Nativeé", legacySelected.getName());
                    assertFalse(legacySelected.usesAsciiClassNames());
                }
                ClassEntity upper = scope.fetchUserClass("NativeÉ", "nativeÉ", true);
                ClassEntity lower = scope.fetchUserClass("Nativeé", "nativeé", true);
                assertNotSame(upper, lower); assertEquals("NativeÉ", upper.getName()); assertEquals("Nativeé", lower.getName());
                assertTrue(upper.usesAsciiClassNames()); assertEquals(!legacyFirst, lower.usesAsciiClassNames());
                if (legacyFirst) assertSame(legacySelected, lower);
                scope.registerClass("NativeAliasÉ", upper); scope.registerClass("NativeAliasé", lower);
                assertSame(upper, env.fetchClass("NativeAliasÉ", false, PhpLanguageProfile.PHP_8_5));
                assertSame(lower, env.fetchClass("NativeAliasé", false, PhpLanguageProfile.PHP_8_5));
                assertEquals("NativeÉ|Nativeé|type-error", include(env, compile(env,
                        "function acceptAlias(NativeAliasÉ $value) { return get_class($value); } "
                        + "$a=new NativeAliasÉ; $b=new NativeAliasé; $out=acceptAlias($a).'|'.get_class($b); "
                        + "try { acceptAlias($b); } catch (\\TypeError $e) { $out.='|type-error'; } return $out;",
                        PhpLanguageProfile.PHP_8_5, dump)).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testMixedHierarchyAndStorageNamespaces() throws Throwable {
        for (boolean dump : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            try {
                include(env, compile(env, "interface MixedContractÉ {} trait MixedTraitÉ { function upper() { return 'upper'; } } "
                        + "class StorageÉ { public static $value=3; const c=3; }", PhpLanguageProfile.JPHP_LEGACY, dump));
                ModuleEntity modern = compile(env, "interface MixedContracté {} trait MixedTraité { function lower() { return 'lower'; } } "
                        + "class Storageé { public static $value=7; const c=7; } "
                        + "class MixedBoth implements MixedContractÉ,MixedContracté { use MixedTraitÉ,MixedTraité; } "
                        + "$b=new MixedBoth; Storageé::$value=9; return StorageÉ::$value.'|'.Storageé::$value.'|'.StorageÉ::c.'|'.Storageé::c"
                        + ".'|'.$b->upper().'|'.$b->lower().'|'.(int)($b instanceof MixedContractÉ).(int)($b instanceof MixedContracté);",
                        PhpLanguageProfile.PHP_8_5, dump);
                assertEquals("3|9|3|7|upper|lower|11", include(env, modern).toString());
                ClassEntity both = modern.findClass("MixedBoth"); assertEquals(2, both.getInterfaces().size()); assertEquals(2, both.getTraits().size());
                ClassEntity old = env.fetchClass("StorageÉ", false, PhpLanguageProfile.PHP_8_5), current = modern.findClass("Storageé");
                assertNotEquals(old.findStaticProperty("value").getInternalName(), current.findStaticProperty("value").getInternalName());
                assertNotEquals(old.findConstant("c").getInternalName(), current.findConstant("c").getInternalName());
                // Exercise the runtime dynamic-constant path used by old bytecode, independently of constant folding.
                old.findConstant("c").setValue(null); current.findConstant("c").setValue(null);
                old.addDynamicConstant(env, "c", php.runtime.memory.LongMemory.valueOf(13));
                current.addDynamicConstant(env, "c", php.runtime.memory.LongMemory.valueOf(17));
                assertEquals(13, old.findConstant("c").getValue(env).toLong()); assertEquals(17, current.findConstant("c").getValue(env).toLong());
            } finally { env.doFinal(); }
        }
    }

    private static final class CountingClass extends ClassEntity {
        int initializations;
        CountingClass(Context context) { super(context); }
        @Override public void initEnvironment(Environment env) { initializations++; super.initEnvironment(env); }
    }
    @Test public void testRejectedLegacyScopeFallbackHasNoInitializationSideEffects() throws Throwable {
        CompileScope scope = newScope(); ModuleEntity legacy = new ModuleEntity(new Context(""));
        CountingClass old = new CountingClass(legacy.getContext()); old.setModule(legacy); old.setName("RejectÉ"); scope.registerClass(old);
        Environment env = new Environment(scope);
        try {
            ClassEntity modern = symbol("RejectÉ", PhpLanguageProfile.PHP_8_5); env.registerClass(modern);
            assertNull(env.fetchClass("Rejecté", false, PhpLanguageProfile.JPHP_LEGACY));
            assertEquals(0, old.initializations); assertSame(modern, env.fetchClass("RejectÉ", false, PhpLanguageProfile.PHP_8_5));
        } finally { env.doFinal(); }
    }

    @Test public void testPublicVirtualAutoloadHookAndExplicitLookupMode() throws Throwable {
        CompileScope scope = newScope(); scope.registerLazyClass(null, NativeHost.class);
        final int[] calls = {0}, cachedHandlerCalls = {0};
        scope.addClassEntityFetchHandler(new php.runtime.env.handler.EntityFetchHandler() {
            @Override public void fetch(CompileScope owner, String original, String lower) {
                if (original.equals("CachedController")) { assertEquals("cachedcontroller", lower); cachedHandlerCalls[0]++; }
            }
        });
        scope.registerClass(symbol("CachedController", PhpLanguageProfile.PHP_8_5));
        assertNotNull(scope.fetchUserClass("CachedController", "cachedcontroller", true));
        assertEquals(1, cachedHandlerCalls[0]);
        Environment env = new Environment(scope) {
            @Override public ClassEntity autoloadCall(String name, String lower) {
                calls[0]++;
                if (name.equals("MissingNested")) { assertFalse(isModernClassLookup()); return null; }
                assertEquals("VirtualController", name); assertEquals("virtualcontroller", lower); assertTrue(isModernClassLookup());
                Context legacy = new Context(""); legacy.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                var frame = php.runtime.PhpPropertyWriteContext.enter(this, new TraceInfo(legacy), false);
                try { assertFalse(isModernClassLookup()); } finally { php.runtime.PhpPropertyWriteContext.leave(frame); }
                assertTrue(isModernClassLookup()); assertNull(fetchClass("MissingNested", true, PhpLanguageProfile.JPHP_LEGACY));
                assertTrue(isModernClassLookup());
                Context source = new Context("class VirtualController extends NativeHost {} $GLOBALS['hook_top_level']=1;");
                source.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try { importModule(source); } catch (Throwable error) { throw new RuntimeException(error); }
                return fetchClass(name, false);
            }
        };
        try {
            ModuleEntity dependent = compile(env, "class VirtualChild extends VirtualController {}", PhpLanguageProfile.PHP_8_5, false);
            assertNotNull(dependent.findClass("VirtualChild")); assertEquals(0, calls[0]);
            include(env, dependent); assertEquals(2, calls[0]);
            assertNull(env.getGlobals().getByScalar("hook_top_level")); assertFalse(env.isModernClassLookup());
        } finally { env.doFinal(); }
    }

    @Test public void testDeferredRelationsUsePublicVirtualFetchAndModuleProfile() throws Throwable {
        for (boolean dump : new boolean[] {false, true}) {
            final Map<String, Integer> calls = new HashMap<>();
            Environment env = new Environment(newScope()) {
                @Override public ClassEntity fetchClass(String name, String lower, boolean autoLoad) {
                    if (name.equals("HookParentÉ") || name.equals("HookContractÉ") || name.equals("HookTraitÉ")) {
                        assertTrue(isModernClassLookup());
                        assertEquals(PhpClassNames.key(name, PhpLanguageProfile.PHP_8_5), lower);
                        assertTrue(autoLoad); calls.merge(name, 1, Integer::sum);
                    }
                    return super.fetchClass(name, lower, autoLoad);
                }
            };
            try {
                include(env, compile(env, "class HookParentÉ {} interface HookContractÉ {} "
                        + "trait HookTraitÉ { function probe(){ return 42; } }", PhpLanguageProfile.PHP_8_5, dump));
                assertFalse(env.isModernClassLookup());
                ModuleEntity child = compile(env, "if(true){ class HookChild extends HookParentÉ implements HookContractÉ "
                        + "{ use HookTraitÉ; } } return (new HookChild)->probe();", PhpLanguageProfile.PHP_8_5, dump);
                assertTrue(calls.isEmpty());
                assertEquals(42, include(env, child).toInteger());
                assertEquals(Integer.valueOf(1), calls.get("HookParentÉ"));
                assertEquals(Integer.valueOf(1), calls.get("HookContractÉ"));
                assertEquals(Integer.valueOf(1), calls.get("HookTraitÉ"));
                assertFalse(env.isModernClassLookup());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testExistingVirtualFetchOverridesAndNestedPhpFrame() throws Throwable {
        ClassEntity injected = symbol("Injected", PhpLanguageProfile.PHP_8_5);
        CompileScope scope = new CompileScope() {
            @Override public ClassEntity fetchUserClass(String name, String lower) {
                if (name.equals("ScopeVirtual")) { assertTrue(isModernClassLookup()); return injected; }
                return super.fetchUserClass(name, lower);
            }
        };
        Environment env = new Environment(scope) {
            @Override public ClassEntity fetchClass(String name, String lower, boolean autoLoad) {
                if (name.equals("EnvVirtual")) { assertTrue(isModernClassLookup()); return injected; }
                return super.fetchClass(name, lower, autoLoad);
            }
            @Override public boolean isLoadedClass(String name) {
                if (name.equals("envvirtual")) { assertTrue(isModernClassLookup()); return true; }
                return super.isLoadedClass(name);
            }
        };
        try {
            assertSame(injected, env.fetchClass("EnvVirtual", false, PhpLanguageProfile.PHP_8_5));
            assertSame(injected, env.fetchClass("ScopeVirtual", false, PhpLanguageProfile.PHP_8_5));
            assertTrue(env.isLoadedClass("EnvVirtual", PhpLanguageProfile.PHP_8_5)); assertFalse(env.isModernClassLookup());
            Context caller = new Context(""); caller.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var frame = php.runtime.PhpPropertyWriteContext.enter(env, new TraceInfo(caller), false);
            try {
                // Explicit compiler metadata wins for its captured caller, not for a newly entered PHP callee.
                assertSame(injected, env.fetchClass("EnvVirtual", false, PhpLanguageProfile.PHP_8_5));
                assertFalse(env.isModernClassLookup());
            } finally { php.runtime.PhpPropertyWriteContext.leave(frame); }
        } finally { env.doFinal(); }

        final int[] nestedCalls = {0}; Environment nested = new Environment(newScope()) {
            @Override public ClassEntity autoloadCall(String name, String lower) {
                if (name.equals("NestedPhpProbe")) { nestedCalls[0]++; assertFalse(isModernClassLookup()); return null; }
                assertEquals("OuterModernMissing", name); assertTrue(isModernClassLookup());
                ClassEntity result = super.autoloadCall(name, lower);
                assertTrue(isModernClassLookup()); return result;
            }
        };
        nested.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        try {
            include(nested, compile(nested, "function legacy_autoload_probe($name) { if($name==='OuterModernMissing') class_exists('NestedPhpProbe'); } "
                    + "spl_autoload_register('legacy_autoload_probe');", PhpLanguageProfile.JPHP_LEGACY, false));
            assertNull(nested.fetchClass("OuterModernMissing", true, PhpLanguageProfile.PHP_8_5));
            assertEquals(1, nestedCalls[0]); assertFalse(nested.isModernClassLookup());
        } finally { nested.doFinal(); }
    }

    private static final class TestLoader extends StandaloneLoader {
        int publications;
        void library(byte[] bytes) throws Exception { loadLibraryDump(new ByteArrayInputStream(bytes)); }
        @Override protected void loadModule(ModuleEntity module) { publications++; getScope().loadModule(module); }
    }

    @Test public void testStandaloneOriginalSymbolWireAndRejectedModernFallback() throws Throwable {
        Environment compiler = new Environment(newScope()); TestLoader loader = new TestLoader();
        try {
            Map<String, byte[]> resources = new HashMap<>(); StandaloneLibraryDumper library = new StandaloneLibraryDumper();
            ModuleEntity dependency = compile(compiler, "class HeaderDependency {}", PhpLanguageProfile.PHP_8_5, false);
            include(compiler, dependency); library.addModule(dependency);
            ByteArrayOutputStream dependencyBytes = new ByteArrayOutputStream();
            new ModuleDumper(dependency.getContext(), compiler, true).save(dependency, dependencyBytes);
            resources.put(dependency.getInternalName() + ".dump", dependencyBytes.toByteArray());
            for (String name : new String[] {"WireÉ", "Wireé", "OnlyÉ"}) {
                ModuleEntity module = compile(compiler, "class " + name
                        + (name.equals("OnlyÉ") ? " extends HeaderDependency" : "") + " {}", PhpLanguageProfile.PHP_8_5, false);
                library.addModule(module); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                new ModuleDumper(module.getContext(), compiler, true).save(module, bytes);
                resources.put(module.getInternalName() + ".dump", bytes.toByteArray());
            }
            ModuleEntity old = compile(compiler, "class LegacyWireÉ {}", PhpLanguageProfile.JPHP_LEGACY, false);
            library.addModule(old); ByteArrayOutputStream oldBytes = new ByteArrayOutputStream();
            new ModuleDumper(old.getContext(), compiler, true).save(old, oldBytes); resources.put(old.getInternalName() + ".dump", oldBytes.toByteArray());
            ByteArrayOutputStream wire = new ByteArrayOutputStream(); library.save(wire);
            StandaloneLibrary decoded = new StandaloneLibraryDumper().load(new ByteArrayInputStream(wire.toByteArray()));
            assertNotSame(decoded.getClassModules().get("wireÉ"), decoded.getClassModules().get("wireé"));
            assertTrue(decoded.getClassModules().get("wireÉ").getClasses().contains("WireÉ"));
            final int[] dependencyReads = {0};
            loader.library(wire.toByteArray()); loader.setClassLoader(new ClassLoader(getClass().getClassLoader()) {
                @Override public InputStream getResourceAsStream(String name) {
                    if (name.equals(dependency.getInternalName() + ".dump")) dependencyReads[0]++;
                    byte[] bytes = resources.get(name); return bytes == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(bytes);
                }
            });
            CompileScope scope = loader.getScope();
            assertNull(scope.fetchUserClass("Onlyé", "onlyé", true));
            assertNull(scope.fetchUserClass("Onlyé", "onlyé", false));
            assertEquals(0, loader.publications); assertEquals(0, dependencyReads[0]);
            assertFalse(scope.getClassMap().containsKey("onlyÉ")); assertFalse(scope.getClassMap().containsKey("headerdependency"));
            assertEquals("WireÉ", scope.fetchUserClass("WireÉ", "wireé", true).getName());
            assertEquals("Wireé", scope.fetchUserClass("Wireé", "wireé", true).getName());
            assertEquals("LegacyWireÉ", scope.fetchUserClass("LegacyWireé", "legacywireé", false).getName());
            assertNull(scope.fetchUserClass("LegacyWireé", "legacywireé", true));
            assertEquals(3, loader.publications);
        } finally { loader.getScopeEnvironment().doFinal(); compiler.doFinal(); }
    }
}
