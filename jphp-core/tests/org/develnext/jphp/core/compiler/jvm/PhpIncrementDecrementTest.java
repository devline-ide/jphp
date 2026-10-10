package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.PhpArithmetic;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.BinaryMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.output.serialization.PhpSerialization;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.support.Entity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;

import static org.junit.Assert.*;

/** Native PHP 8.5.11 values, retained aliases, errors and all ordered diagnostics. */
public class PhpIncrementDecrementTest extends JvmCompilerCase {
    private String resource(String name, String suffix) throws IOException {
        String path = "resources/type_hinting/" + name + "." + suffix;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ModuleEntity compile(Environment env, String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Context context = new Context(source);
        context.setPhpLanguageProfile(profile);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module);
        env.getScope().addUserModule(module);
        env.registerModule(module);
        ArrayList<Entity> entities = new ArrayList<>();
        entities.add(module);
        entities.addAll(module.getClasses());
        entities.addAll(module.getFunctions());
        entities.addAll(module.getClosures());
        entities.addAll(module.getGenerators());
        StringWriter diagnostics = new StringWriter();
        PrintWriter writer = new PrintWriter(diagnostics);
        for (Entity entity : entities) if (entity.getData() != null && entity.getData().length != 0) {
            CheckClassAdapter.verify(new ClassReader(entity.getData()), env.getScope().getClassLoader(), false, writer);
        }
        assertEquals("ASM profile=" + profile + " dump=" + dumped, "", diagnostics.toString());
        return module;
    }

    private TraceInfo trace(ModuleEntity module) {
        return TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
    }

    private String wire(Environment env, TraceInfo trace, Memory value) throws Throwable {
        return PhpSerialization.serialize(env, trace, value).toString();
    }

    private void verifyFixture(boolean debug) throws Throwable {
        ArrayList<String> differences = new ArrayList<>();
        java.util.LinkedHashMap<String, ArrayList<String>> failingRows = new java.util.LinkedHashMap<>();
        for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();
            scope.setDebugMode(debug);
            scope.registerExtension(new StandardExtension());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(scope, output);
            try {
                String source = (strict ? "declare(strict_types=1);" : "")
                        + resource("php85_increment_decrement", "php").substring(5);
                ModuleEntity module = compile(env, source, PhpLanguageProfile.PHP_8_5, dumped);
                // Compilation stores the module contract independently of the later Scope profile.
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                ArrayMemory actual = module.include(env).toValue(ArrayMemory.class);
                TraceInfo trace = trace(module);
                ArrayMemory expected = PhpSerialization.unserialize(env, trace, StringMemory.valueOf(
                        resource(strict ? "php85_increment_decrement_strict" : "php85_increment_decrement", "expected")))
                        .toValue(ArrayMemory.class);
                String mode = "debug=" + debug + " strict=" + strict + " dump=" + dumped;
                assertEquals(mode, 384, actual.size());
                assertEquals(mode, 384, expected.size());
                for (int row = 0; row < expected.size(); row++) {
                    String wanted = wire(env, trace, expected.valueOfIndex(row));
                    String got = wire(env, trace, actual.valueOfIndex(row));
                    if (!wanted.equals(got)) {
                        String label = row + ":" + expected.valueOfIndex(row).valueOfIndex(0).toString();
                        failingRows.computeIfAbsent(mode, key -> new ArrayList<>()).add(label);
                        differences.add(mode + " row=" + label + " expected=" + wanted + " actual=" + got);
                    }
                }
                env.getDefaultBuffer().flush();
                assertEquals("All diagnostics must be captured in rows: " + mode, "", output.toString(StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        }
        StringBuilder report = new StringBuilder();
        failingRows.forEach((mode, rows) -> report.append(mode).append(" count=").append(rows.size())
                .append(" rows=").append(rows).append("\n"));
        assertDifferences(report.toString(), differences);
    }

    private void assertDifferences(String summary, ArrayList<String> differences) {
        String details = String.join("\n", differences.subList(0, Math.min(20, differences.size())));
        assertEquals(summary + "Total differences=" + differences.size() + "\nFirst 20 details:\n" + details,
                0, differences.size());
    }

    @Test public void testModernIncrementDecrementWithDynamicLocals() throws Throwable { verifyFixture(true); }
    @Test public void testModernIncrementDecrementWithOptimizedLocals() throws Throwable { verifyFixture(false); }

    private ArrayMemory byteView(Environment env, TraceInfo trace, Memory value) throws Throwable {
        value = value.toValue();
        boolean string = value.isString();
        String type = string ? "string" : value.getRealType() == Memory.Type.DOUBLE ? "double" : "integer";
        ArrayMemory view = new ArrayMemory();
        view.add(type);
        view.add(string ? HexFormat.of().formatHex(value.getBinaryBytes(StandardCharsets.UTF_8)) : wire(env, trace, value));
        return view;
    }

    /** The native source contains invalid UTF8; the Java target must receive the original byte array. */
    @Test public void testRawBinaryTargetsAgainstNativeBytes() throws Throwable {
        String[] labels = {"invalid-prefix", "invalid-tail", "invalid-middle", "numeric99", "numeric9",
                "nul-prefix", "nul-middle", "ascii-z", "utf8"};
        byte[][] samples = {{(byte) 0xff, 'z'}, {(byte) 0xff}, {'a', (byte) 0xff, 'z'}, {'9', '9'}, {'9'},
                {0, 'z'}, {'a', 0, 'z'}, {'z'}, {(byte) 0xc3, (byte) 0xa9}};
        String[] modes = {"post++", "pre++", "post--", "pre--"};
        for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();
            scope.registerExtension(new StandardExtension());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(scope, output);
            try {
                ModuleEntity module = compile(env, "$id_byte_events=[];set_error_handler(function($s,$m)use(&$id_byte_events){"
                        + "$id_byte_events[]=[$s,$m];return true;});", PhpLanguageProfile.PHP_8_5, dumped);
                module.include(env);
                TraceInfo trace = trace(module);
                ArrayMemory expected = PhpSerialization.unserialize(env, trace, StringMemory.valueOf(
                        resource("php85_increment_decrement_binary", "expected"))).toValue(ArrayMemory.class);
                assertEquals(36, expected.size());
                ArrayList<String> differences = new ArrayList<>();
                int row = 0;
                for (int sample = 0; sample < samples.length; sample++) for (int mode = 0; mode < modes.length; mode++) {
                    env.getGlobals().refOfIndex("id_byte_events").assign(new ArrayMemory());
                    ReferenceMemory target = new ReferenceMemory(new BinaryMemory(samples[sample].clone()));
                    Memory result = PhpArithmetic.incrementTarget(target, mode >= 2, mode % 2 == 0, env, trace);
                    ArrayMemory actual = new ArrayMemory();
                    actual.add(labels[sample] + ":" + modes[mode]);
                    actual.add(byteView(env, trace, result));
                    actual.add(byteView(env, trace, target));
                    actual.add(env.getGlobals().valueOfIndex("id_byte_events"));
                    String wanted = wire(env, trace, expected.valueOfIndex(row));
                    String got = wire(env, trace, actual);
                    if (!wanted.equals(got)) differences.add("dump=" + dumped + " row=" + row + " expected=" + wanted + " actual=" + got);
                    row++;
                }
                assertDifferences("Binary dump=" + dumped + "\n", differences);
                env.getDefaultBuffer().flush();
                assertEquals("Byte diagnostics must be captured", "", output.toString(StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        }
    }

    @Test public void testLegacyIncDecContractIndependentOfScope() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();
            scope.setDebugMode(debug);
            scope.registerExtension(new StandardExtension());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(scope, output);
            try {
                ModuleEntity module = compile(env, "class IDLegacy{public $v='Z';}function id_legacy($v){$before=$v++;return [$before,$v];}"
                        + "$events=[];set_error_handler(function($s,$m)use(&$events){$events[]=[$s,$m];return true;});"
                        + "$o=new IDLegacy;$before=$o->v++;$a=['Z'];$array=$a[0]++;restore_error_handler();"
                        + "return [id_legacy('Z'),$before,$o->v,$array,$a[0],$events];", PhpLanguageProfile.JPHP_LEGACY, dumped);
                scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                Memory actual = module.include(env);
                // Physical pre-incdec JAR baseline returns the mutated property cell for post++.
                assertEquals("a:6:{i:0;a:2:{i:0;s:1:\"Z\";i:1;s:2:\"AA\";}i:1;i:1;i:2;i:1;"
                        + "i:3;s:1:\"Z\";i:4;s:2:\"AA\";i:5;a:0:{}}", wire(env, trace(module), actual));
                env.getDefaultBuffer().flush();
                assertEquals("Legacy diagnostics", "", output.toString(StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        }
    }
}
