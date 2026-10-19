package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.common.CompilerFactory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.reflection.ModuleEntity;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.BinaryMemory;
import php.runtime.memory.output.serialization.PhpSerialization;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LanguageProfileTest extends JvmCompilerCase {
    @Test
    public void testModuleProfileControlsFoldedAndDynamicComparisons() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            String code = "function f($a,$b) { return $a == $b; } function id($x) { return $x; } " +
                    "return [0 == 'abc', f(0,'abc'), id(0) == id('abc'), id('abc') == id(0), " +
                    "0 < 'abc', 'abc' > 0, 0 <=> 'abc', 'abc' <=> 0, " +
                    "id(0) <=> id('abc'), id('abc') <=> id(0)];";
            for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
                Memory result = runProfile(code, profile, dumped);
                boolean legacy = profile == PhpLanguageProfile.JPHP_LEGACY;
                for (int i = 0; i < 4; i++) assertEquals(legacy, result.valueOfIndex(null, i).toBoolean());
                assertEquals(!legacy, result.valueOfIndex(null, 4).toBoolean());
                assertEquals(!legacy, result.valueOfIndex(null, 5).toBoolean());
                for (int i = 6; i < 10; i++) {
                    assertEquals(legacy ? 0 : (i % 2 == 0 ? -1 : 1), result.valueOfIndex(null, i).toLong());
                }
            }
        }
    }

    @Test
    public void testConcatPrecedenceAndSwitchUseModuleProfile() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals("x3", runProfile("return 'x' . 1 + 2;", PhpLanguageProfile.PHP_8_5, dumped).toString());
            assertEquals("x4", runProfile("function f($x) { return 'x' . $x << 2; } return f(1);",
                    PhpLanguageProfile.PHP_8_5, dumped).toString());
            assertEquals(2, runProfile("return 'x' . 1 + 2;", PhpLanguageProfile.JPHP_LEGACY, dumped).toLong());
            String code = "switch ('abc') { case 0: return 0; case 'abc': return 1; default: return 2; }";
            assertEquals(1, runProfile(code, PhpLanguageProfile.PHP_8_5, dumped).toLong());
            assertEquals(0, runProfile(code, PhpLanguageProfile.JPHP_LEGACY, dumped).toLong());
        }
    }

    @Test
    public void testEvalInheritsCallerProfile() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            assertFalse(runProfile("return eval('return 0 == \"abc\";');", PhpLanguageProfile.PHP_8_5, dumped).toBoolean());
            assertTrue(runProfile("return eval('return 0 == \"abc\";');", PhpLanguageProfile.JPHP_LEGACY, dumped).toBoolean());
        }
    }

    @Test
    public void testCompilerFactoryAndProfileAreInheritedByChildScopes() throws Throwable {
        CompileScope parent = newScope();
        CompilerFactory original = parent.getCompilerFactory();
        AtomicInteger calls = new AtomicInteger();
        parent.setCompilerFactory(new CompilerFactory() {
            @Override
            public php.runtime.common.AbstractCompiler getCompiler(Environment env, Context context) throws Throwable {
                calls.incrementAndGet();
                return original.getCompiler(env, context);
            }
        });
        parent.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        CompileScope child = new CompileScope(parent);
        Environment env = new Environment(child);
        assertEquals(PhpLanguageProfile.PHP_8_5, child.getPhpLanguageProfile());
        assertSame(parent.getCompilerFactory(), child.getCompilerFactory());
        assertFalse(env.eval("return 0 == 'abc';").toBoolean());
        assertEquals(1, calls.get());
        env.doFinal();
    }

    @Test
    public void testLegacyDumpAndIncompatibleHeaders() throws Throwable {
        Environment env = new Environment(newScope());
        Context context = new Context("return 123;");
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        ModuleDumper dumper = new ModuleDumper(context, env, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        dumper.save(module, bytes);
        byte[] modern = bytes.toByteArray();
        // A current body cannot become a historical format by replacing its header.
        // Use the original ABI0 bytes to exercise the real legacy reader layout.
        try (InputStream legacy = getClass().getClassLoader().getResourceAsStream("resources/legacy_jphp.phb")) {
            assertNotNull(legacy);
            ModuleEntity loaded = dumper.load(legacy);
            assertEquals(PhpLanguageProfile.JPHP_LEGACY, loaded.getPhpLanguageProfile());
            env.getScope().loadModule(loaded);
            env.getScope().addUserModule(loaded);
            env.registerModule(loaded);
            assertEquals(42, loaded.include(env).toLong());
        }
        for (int offset : new int[] {4, 8, 12}) {
            byte[] invalid = modern.clone();
            invalid[offset] = 0x7f;
            try {
                dumper.load(new ByteArrayInputStream(invalid));
                fail("Invalid dump version/ABI/profile was accepted at " + offset);
            } catch (DumpException expected) {
                assertTrue(expected.getMessage().contains(offset == 4 ? "version" : offset == 8 ? "ABI" : "profile"));
            }
        }
        env.doFinal();
    }

    @Test
    public void testLoadsOriginalAbi6PropertyDump() throws Throwable {
        Environment env = new Environment(newScope());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/abi6_jphp.phb")) {
            assertNotNull(input);
            ModuleEntity module = new ModuleDumper(new Context("return 0;"), env, true).load(input);
            env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
            assertEquals(7, module.include(env).toLong());
            var clazz=module.findClass("abi6typed");
            assertEquals("int", clazz.findProperty("i").getDeclaredType().getSignature());
            assertTrue(clazz.findProperty("i").getDefaultValue().isUndefined());
            assertEquals("?int", clazz.findProperty("nil").getDeclaredType().getSignature());
            assertFalse(clazz.findProperty("nil").hasDefaultValue());
            assertTrue(clazz.findProperty("name").isFinal());
            assertEquals("x", clazz.findProperty("name").getDefaultValue().toString());
        } finally {env.doFinal();}
    }

    @Test
    public void testLoadsOriginalAbi5ModernDump() throws Throwable {
        Environment env = new Environment(newScope());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/abi5_jphp.phb")) {
            assertNotNull(input);
            ModuleEntity module = new ModuleDumper(new Context("return 0;"), env, true).load(input);
            env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
            assertEquals(7, module.include(env).toLong());
            var method = module.findClass("abi5typed").findMethod("f");
            assertEquals("int|float", method.getReturnTypeChecker().getSignature());
            assertEquals("int|float", method.getParameters()[0].getTypeChecker().getSignature());
            assertEquals(2, module.findClass("abi5typed").findProperty("legacy").getDefaultValue().toLong());
        } finally {env.doFinal();}
    }

    @Test
    public void testLoadsOriginalAbi4ModernDump() throws Throwable {
        Environment env = new Environment(newScope());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/abi4_jphp.phb")) {
            assertNotNull(input);
            ModuleEntity module = new ModuleDumper(new Context("return 0;"), env, true).load(input);
            env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
            assertEquals(7, module.include(env).toLong());
            assertEquals("int", module.findClass("abi4typed").findConstant("X").getDeclaredType().getSignature());
        } finally {env.doFinal();}
    }

    @Test
    public void testLoadsOriginalAbi3ModernDump() throws Throwable {
        Environment env = new Environment(newScope());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/abi3_jphp.phb")) {
            assertNotNull(input);
            ModuleEntity module = new ModuleDumper(new Context("return 0;"), env, true).load(input);
            env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
            assertEquals(5, module.include(env).toLong());
            assertNotNull(module.findClass("abi3lazy").findConstant("X").getInitializerMethod());
            assertNull(module.findClass("abi3lazy").findConstant("X").getDeclaredType());
        } finally {env.doFinal();}
    }

    @Test
    public void testLoadsOriginalAbi2ModernDump() throws Throwable {
        Environment env = new Environment(newScope());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/abi2_jphp.phb")) {
            assertNotNull(input);
            ModuleEntity module = new ModuleDumper(new Context("return 0;"), env, true).load(input);
            env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
            assertEquals(3, module.include(env).toLong());
            assertTrue(module.findClass("abi2constant").findConstant("X").isFinal());
        } finally { env.doFinal(); }
    }

    @Test
    public void testLoadsOriginalAbi1ModernDump() throws Throwable {
        Environment env = new Environment(newScope());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/abi1_jphp.phb")) {
            assertNotNull(input);
            ModuleEntity module = new ModuleDumper(new Context("return 0;"), env, true).load(input);
            assertEquals(PhpLanguageProfile.PHP_8_5, module.getPhpLanguageProfile());
            env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
            assertEquals(3, module.include(env).toLong());
            assertFalse(module.findClass("abi1constant").findConstant("X").isFinal());
        } finally { env.doFinal(); }
    }

    @Test
    public void testLoadsBaselineJava6BytecodeAndOldDump() throws Throwable {
        Environment env = new Environment(newScope());
        Context context = new Context("return 0;");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/legacy_jphp.phb")) {
            assertNotNull(input);
            ModuleEntity module = new ModuleDumper(context, env, true).load(input);
            assertEquals(PhpLanguageProfile.JPHP_LEGACY, module.getPhpLanguageProfile());
            assertLegacyClassfile(module.getData());
            module.getFunctions().forEach(function -> assertLegacyClassfile(function.getData()));
            module.getClasses().forEach(clazz -> assertLegacyClassfile(clazz.getData()));
            module.getClosures().forEach(closure -> assertLegacyClassfile(closure.getData()));
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            env.getScope().loadModule(module);
            env.getScope().addUserModule(module);
            env.registerModule(module);
            assertEquals(42, module.include(env).toLong());
        } finally {
            env.doFinal();
        }
    }

    private static void assertLegacyClassfile(byte[] data) {
        assertNotNull(data);
        assertEquals(50, new ClassReader(data).readUnsignedShort(6));
    }

    @Test
    public void testComparisonMatrixAgainstNativePhp83() throws Throwable {
        String source = resource("resources/expressions/php8_comparison_matrix.php").substring("<?php".length());
        String expected = resource("resources/expressions/php8_comparison_matrix.expected").replace("\r\n", "\n");
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals(expected, runProfile(source, PhpLanguageProfile.PHP_8_5, dumped).toString());
        }
    }

    @Test
    public void testRecursiveComparisonsThrowCatchableError() throws Throwable {
        String code = "$a = []; $a['self'] = &$a; $b = []; $b['self'] = &$b; " +
                "try { $same = $a == $b; } catch (Error $e) { return 'caught'; } return 'missed';";
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals("caught", runProfile(code, PhpLanguageProfile.PHP_8_5, dumped).toString());
        }
    }

    @Test
    public void testUnsupportedAttributesAreNotSilentlyDiscarded() throws Throwable {
        // Unknown attribute classes remain lazy: preserve metadata, then reject actual instantiation.
        String inspect = "$a=(new ReflectionClass('C'))->getAttributes(); "
                + "try{$a[0]->newInstance();return 'unexpected constructor';}"
                + "catch(Error $e){return count($a).':'.$a[0]->getName().':'.get_class($e).':'.$e->getMessage();}";
        for (boolean dumped : new boolean[] {false, true}) {
            for (String prefix : new String[] {"#[A] class C {} ", "#[A]\nclass C {}\n"}) {
                assertEquals(1, runProfile(prefix + "return 1;", PhpLanguageProfile.PHP_8_5, dumped).toLong());
                assertEquals("1:A:Error:Attribute class \"A\" not found",
                        runProfile(prefix + inspect, PhpLanguageProfile.PHP_8_5, dumped).toString());
            }
            assertEquals(1, runProfile("# A normal comment\nreturn 1;", PhpLanguageProfile.PHP_8_5, dumped).toLong());
            assertEquals(1, runProfile("#[A]\nclass C {}\nreturn 1;", PhpLanguageProfile.JPHP_LEGACY, dumped).toLong());
        }
    }

    @Test
    public void testTernaryAssociativity() throws Throwable {
        for (String code : new String[] {"return true ? 1 : false ? 2 : 3;",
                "return true ? 1 : false ?: 3;", "return true ?: false ? 2 : 3;"}) {
            try {
                runProfile(code, PhpLanguageProfile.PHP_8_5, false);
                fail("Unparenthesized nested ternary was accepted");
            } catch (php.runtime.exceptions.support.ErrorException expected) {
                assertTrue(expected.getMessage().contains("ternary"));
            }
        }
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals(1, runProfile("return true ? 1 : (false ? 2 : 3);", PhpLanguageProfile.PHP_8_5, dumped).toLong());
            assertEquals(3, runProfile("return (true ? 1 : false) ? 3 : 4;", PhpLanguageProfile.PHP_8_5, dumped).toLong());
            assertEquals(2, runProfile("return true ? false ? 1 : 2 : 3;", PhpLanguageProfile.PHP_8_5, dumped).toLong());
            assertEquals(3, runProfile("return false ?: false ?: 3;", PhpLanguageProfile.PHP_8_5, dumped).toLong());
        }
    }

    @Test
    public void testArraySearchProfilesAndStrictContracts() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
                Memory result = runProfile("function identity($x) { return $x; } " +
                        "return [in_array(0, ['abc']), in_array(identity(0), ['abc']), " +
                        "array_search(1, ['1'], true), array_keys(['1'], 1, true), " +
                        "array_search(0, ['abc']), array_keys(['abc'], 0)];", profile, dumped);
                boolean legacy = profile == PhpLanguageProfile.JPHP_LEGACY;
                assertEquals(legacy, result.valueOfIndex(null, 0).toBoolean());
                assertEquals(legacy, result.valueOfIndex(null, 1).toBoolean());
                assertSame(Memory.FALSE, result.valueOfIndex(null, 2).toValue());
                assertEquals(0, result.valueOfIndex(null, 3).toValue(php.runtime.memory.ArrayMemory.class).size());
                assertEquals(legacy ? "0" : "", result.valueOfIndex(null, 4).toString());
                assertEquals(legacy ? 1 : 0, result.valueOfIndex(null, 5).toValue(php.runtime.memory.ArrayMemory.class).size());
            }
        }
    }

    @Test
    public void testArraySearchMatrixAgainstNativePhp83() throws Throwable {
        String source = resource("resources/arrays/php8_search_matrix.php").substring("<?php".length());
        String expected = resource("resources/arrays/php8_search_matrix.expected").replace("\r\n", "\n");
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals(expected, runProfile(source, PhpLanguageProfile.PHP_8_5, dumped).toString());
        }
    }

    @Test
    public void testSortContractsAgainstNativePhp83() throws Throwable {
        String source = resource("resources/arrays/php8_sort_contract.php").substring("<?php".length());
        String expected = resource("resources/arrays/php8_sort_contract.expected").replace("\r\n", "\n");
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals(expected, runProfile(source, PhpLanguageProfile.PHP_8_5, dumped).toString());
        }
    }

    @Test
    public void testTraceProfileFallbacks() throws Throwable {
        CompileScope scope = newScope();
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        Environment env = new Environment(scope);
        assertEquals(PhpLanguageProfile.PHP_8_5, env.getModuleManager().getPhpLanguageProfile(php.runtime.env.TraceInfo.UNKNOWN));
        assertEquals(PhpLanguageProfile.JPHP_LEGACY, env.getModuleManager().getPhpLanguageProfile(
                php.runtime.env.TraceInfo.valueOf(null, 0L, 0L)));
        Context context = new Context("return 1;");
        context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        assertEquals(PhpLanguageProfile.JPHP_LEGACY, env.getModuleManager().getPhpLanguageProfile(new php.runtime.env.TraceInfo(context)));
        env.doFinal();
    }

    @Test
    public void testArrayCopiesPreserveOnlyExplicitReferences() throws Throwable {
        String code = "$x = 1; $a = [&$x]; $b = $a; $b[] = 2; $x = 3; " +
                "$ok1 = $a[0] === 3 && $b[0] === 3; $b[0] = 4; $ok2 = $x === 4 && $a[0] === 4; " +
                "$x = 1; $a = ['x' => &$x]; $b = $a; $b['extra'] = 2; $x = 3; " +
                "$ok3 = $a['x'] === 3 && $b['x'] === 3; $b['x'] = 4; $ok4 = $x === 4 && $a['x'] === 4; " +
                "$k = 'key'; $v = 1; $a = [$k => $v]; $v = 2; $k = 'other'; " +
                "$ok5 = $a['key'] === 1; " +
                "function array_key() { echo ''; return 'a'; } function array_value() { echo ''; return 7; } " +
                "$a = [array_key() => array_value()]; " +
                "function dynamic_entry($key, $value) { return [strlen($key) => abs($value)]; } " +
                "$dynamic = dynamic_entry('abc', -7); " +
                "return $ok1 && $ok2 && $ok3 && $ok4 && $ok5 && $a['a'] === 7 && $dynamic[3] === 7;";
        for (boolean dumped : new boolean[] {false, true}) {
            for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
                assertTrue(runProfile(code, profile, dumped).toBoolean());
            }
        }
    }

    @Test
    public void testDynamicPrimitiveComparisonOperands() throws Throwable {
        String code = "function comparisons($a, $b) { return [strlen($a) <=> strlen($b), " +
                "strlen($b) <=> strlen($a), strlen($a) == strlen($b), strlen($a) < strlen($b)]; } " +
                "return comparisons('a', 'abc');";
        for (boolean dumped : new boolean[] {false, true}) {
            Memory result = runProfile(code, PhpLanguageProfile.PHP_8_5, dumped);
            assertEquals(-1, result.valueOfIndex(null, 0).toLong());
            assertEquals(1, result.valueOfIndex(null, 1).toLong());
            assertFalse(result.valueOfIndex(null, 2).toBoolean());
            assertTrue(result.valueOfIndex(null, 3).toBoolean());
        }
    }

    @Test
    public void testPhp85SerializationGraphs() throws Throwable {
        String source = resource("resources/serialization/php8_graphs.php").substring("<?php".length());
        String expected = resource("resources/serialization/php8_graphs.expected").replace("\r\n", "\n");
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals(expected, runProfile(source, PhpLanguageProfile.PHP_8_5, dumped).toString());
        }
    }

    @Test
    public void testSerializationBinaryStringsAndDeepGraphs() throws Throwable {
        Environment env = new Environment(newScope());
        env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        env.setErrorFlags(0);
        try {
            byte[] bytes = new byte[] {0, (byte) 0xff, (byte) 0x80, '"', ';', '}'};
            Memory serialized = PhpSerialization.serialize(env, php.runtime.env.TraceInfo.UNKNOWN, new BinaryMemory(bytes));
            assertEquals(13, serialized.getBinaryBytes(StandardCharsets.UTF_8).length);
            assertArrayEquals(bytes, PhpSerialization.unserialize(env, php.runtime.env.TraceInfo.UNKNOWN, serialized)
                    .getBinaryBytes(StandardCharsets.UTF_8));
            Memory nested = Memory.NULL;
            for (int i = 0; i < 10000; i++) { ArrayMemory array = new ArrayMemory(); array.add(nested); nested = array; }
            serialized = PhpSerialization.serialize(env, php.runtime.env.TraceInfo.UNKNOWN, nested);
            assertSame(Memory.FALSE, PhpSerialization.unserialize(env, php.runtime.env.TraceInfo.UNKNOWN, serialized));
            ArrayMemory options = new ArrayMemory(); options.put("max_depth", Memory.CONST_INT_0);
            Memory restored = PhpSerialization.unserialize(env, php.runtime.env.TraceInfo.UNKNOWN, serialized, options);
            for (int i = 0; i < 10000; i++) restored = restored.valueOfIndex(null, 0).toValue();
            assertTrue(restored.isNull());
            for (boolean dumped : new boolean[] {false, true}) {
                assertTrue(runProfile("declare(strict_types=1); try { unserialize(1); return false; } " +
                        "catch (TypeError $e) { return true; }", PhpLanguageProfile.PHP_8_5, dumped).toBoolean());
            }
        } finally { env.doFinal(); }
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Memory runProfile(String code, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Environment env = new Environment(newScope());
        env.getScope().registerExtension(new StandardExtension());
        env.setErrorFlags(0);
        Context context = new Context(code);
        context.setPhpLanguageProfile(profile);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        assertEquals(profile, module.getPhpLanguageProfile());
        // Prove that the compiled module owns semantics, rather than the live scope.
        env.getScope().setPhpLanguageProfile(profile == PhpLanguageProfile.JPHP_LEGACY
                ? PhpLanguageProfile.PHP_8_5 : PhpLanguageProfile.JPHP_LEGACY);
        env.getScope().loadModule(module);
        env.getScope().addUserModule(module);
        env.registerModule(module);
        StringWriter diagnostics = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(module.getData()), env.getScope().getClassLoader(),
                false, new PrintWriter(diagnostics));
        assertEquals("", diagnostics.toString());
        try {
            return module.include(env);
        } finally {
            env.doFinal();
        }
    }
}
