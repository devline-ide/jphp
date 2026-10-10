package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import php.runtime.memory.ArrayMemory;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class PhpSyntaxTest extends JvmCompilerCase {
    @Test public void testClassConstantCompileBoundaries() throws Throwable {
        var mismatches = new java.util.ArrayList<String>();
        for (String row : resource("php85_class_constant_syntax.tsv").split("\\R")) {
            String[] fields = row.split("\t", -1);
            String code = new String(java.util.Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8);
            for (boolean dumped : new boolean[] {false, true}) {
                if (dumped && fields[1].equals("reject")) continue;
                try {
                    run(code, PhpLanguageProfile.PHP_8_5, dumped);
                    if (fields[1].equals("reject")) mismatches.add(fields[0] + ": unexpectedly compiled");
                } catch (ErrorException error) {
                    if (fields[1].equals("accept")) mismatches.add(fields[0] + ": " + error.getMessage());
                    else assertNotNull(error.getTraceInfo());
                }
            }
        }
        assertEquals(String.join("\n", mismatches), 0, mismatches.size());
    }

    @Test public void testNullCoalescingAssignmentBasics() throws Throwable {
        String code = "$a = null; $calls = 0; function rhs(&$calls) { ++$calls; return 7; } " +
                "$r = ($a ??= rhs($calls)); $a ??= rhs($calls); $b = []; $b['x']['y'] ??= count($b); " +
                "$copy = $b; $b['x']['y'] = 9; $zero = 0; $zero ??= rhs($calls); " +
                "return [$a, $r, $calls, $copy['x']['y'], $zero];";
        for (boolean dumped : new boolean[] {false, true}) {
            Memory result = run(code, PhpLanguageProfile.PHP_8_5, dumped);
            long[] expected = {7, 7, 1, 0, 0};
            for (int i = 0; i < expected.length; i++) assertEquals("row " + i, expected[i], result.valueOfIndex(null, i).toLong());
        }
    }

    @Test public void testNumericLiteralsAgainstPhp85() throws Throwable {
        String source = resource("php8_numeric_literals.php").substring(5);
        String[] expected = resource("php8_numeric_literals.expected").trim().split("\\r?\\n");
        for (boolean dumped : new boolean[] {false, true}) {
            Memory result = run(source, PhpLanguageProfile.PHP_8_5, dumped);
            assertEquals(expected.length, result.toValue(ArrayMemory.class).size());
            for (int i = 0; i < expected.length; i++) {
                String[] fields = expected[i].split("\\t");
                Memory actual = result.valueOfIndex(null, i).toValue();
                if (fields[0].equals("integer")) {
                    assertEquals("row " + i, Memory.Type.INT, actual.getRealType());
                    assertEquals("row " + i, Long.parseLong(fields[1]), actual.toLong());
                } else {
                    assertEquals("row " + i, Memory.Type.DOUBLE, actual.getRealType());
                    double value = fields[1].equals("INF") ? Double.POSITIVE_INFINITY : Double.parseDouble(fields[1]);
                    assertEquals("row " + i, Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(actual.toDouble()));
                }
            }
        }
    }

    @Test public void testNullCoalescingAssignmentAgainstPhp85() throws Throwable {
        String source = resource("php8_coalesce_assignment.php").substring(5);
        String expected = resource("php8_coalesce_assignment.expected").replace("\r\n", "\n");
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals(expected, run(source, PhpLanguageProfile.PHP_8_5, dumped).toString());
        }
    }

    @Test public void testCoalescingInvalidTargetsAndBinaryOffsets() throws Throwable {
        for (String expression : new String[] {"1 ??= 2", "foo() ??= 2", "($a) ??= 2", "$a[] ??= 2",
                "C::VALUE ??= 2", "[$a] ??= 2", "$this ??= 1", "$GLOBALS ??= 1", "++$a ??= 2"}) {
            try {
                run("return " + expression + ";", PhpLanguageProfile.PHP_8_5, false);
                fail("Invalid target accepted: " + expression);
            } catch (ErrorException expected) { assertNotNull(expected.getTraceInfo()); }
        }
        try {
            run("$a ??= 1; return $a;", PhpLanguageProfile.JPHP_LEGACY, false);
            fail("Modern assignment leaked into legacy");
        } catch (ErrorException expected) { assertEquals(ErrorType.E_PARSE, expected.getType()); }
        for (boolean dumped : new boolean[] {false, true}) {
            assertArrayEquals(new byte[] {(byte) 0xc3}, run("$s = 'é'; return $s[0] ??= 'z';",
                    PhpLanguageProfile.PHP_8_5, dumped).getBinaryBytes(StandardCharsets.UTF_8));
            Memory result = run("$s = 'é'; $r = ($s[2] ??= 'ñ'); return [$s, $r];", PhpLanguageProfile.PHP_8_5, dumped);
            assertArrayEquals(new byte[] {(byte) 0xc3, (byte) 0xa9, (byte) 0xc3}, result.valueOfIndex(null, 0).getBinaryBytes(StandardCharsets.UTF_8));
            assertArrayEquals(new byte[] {(byte) 0xc3}, result.valueOfIndex(null, 1).getBinaryBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void testTrailingCallCommasAgainstPhp85() throws Throwable {
        String source = resource("php8_trailing_calls.php").substring(5);
        String expected = resource("php8_trailing_calls.expected").replace("\r\n", "\n");
        for (boolean dumped : new boolean[] {false, true}) {
            assertEquals(expected, run(source, PhpLanguageProfile.PHP_8_5, dumped).toString());
        }
    }

    @Test public void testMalformedNumericLiteralsAndCallsAreParseErrors() throws Throwable {
        String[] expressions = {"1__0", "1_", "0x_FF", "0xFF_", "0x", "0b", "0o", "0b102", "0o8", "08",
                "0_8", "1_.0", "1._0", ".1_", "1e_2", "1e+_2", "1e2_", "1e+", "0o_7", "0x1and 0",
                "f(,)", "f(1,,)", "new C(,)", "empty($x,)", "print(1,)"};
        for (String expression : expressions) {
            try {
                run("return " + expression + ";", PhpLanguageProfile.PHP_8_5, false);
                fail("Accepted: " + expression);
            } catch (ErrorException expected) {
                assertEquals(expression, ErrorType.E_PARSE, expected.getType());
                assertNotNull(expected.getTraceInfo());
            }
        }
    }

    @Test public void testKeywordBoundariesAndLegacyRemainAvailable() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            assertFalse(run("return 1and 0;", PhpLanguageProfile.PHP_8_5, dumped).toBoolean());
            assertTrue(run("return 1or 0;", PhpLanguageProfile.PHP_8_5, dumped).toBoolean());
            assertFalse(run("return 1e2and false;", PhpLanguageProfile.PHP_8_5, dumped).toBoolean());
            assertEquals("1_0", run("return '1_0';", PhpLanguageProfile.PHP_8_5, dumped).toString());
            assertEquals(271, run("return 0xFF + 010 + 0b1000;", PhpLanguageProfile.JPHP_LEGACY, dumped).toLong());
        }
        for (String expression : new String[] {"1_000", "0o77", "f(1,)"}) {
            try {
                run("return " + expression + ";", PhpLanguageProfile.JPHP_LEGACY, false);
                fail("Modern syntax leaked into legacy: " + expression);
            } catch (ErrorException expected) { assertEquals(ErrorType.E_PARSE, expected.getType()); }
        }
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/expressions/" + name)) {
            assertNotNull(name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Memory run(String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Environment env = new Environment(newScope());
        env.getScope().registerExtension(new StandardExtension());
        env.setErrorFlags(0);
        try {
            Context context = new Context(source);
            context.setPhpLanguageProfile(profile);
            ModuleEntity module = new JvmCompiler(env, context).compile(false);
            if (dumped) {
                ModuleDumper dumper = new ModuleDumper(context, env, true);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                dumper.save(module, bytes);
                module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            env.getScope().setPhpLanguageProfile(profile == PhpLanguageProfile.PHP_8_5
                    ? PhpLanguageProfile.JPHP_LEGACY : PhpLanguageProfile.PHP_8_5);
            env.getScope().loadModule(module);
            env.getScope().addUserModule(module);
            env.registerModule(module);
            StringWriter diagnostics = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(module.getData()), env.getScope().getClassLoader(),
                    false, new PrintWriter(diagnostics));
            assertEquals("", diagnostics.toString());
            return module.include(env);
        } finally { env.doFinal(); }
    }
}
