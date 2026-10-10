package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.junit.After;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.PhpNumericConversions;
import php.runtime.PhpNumericString;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.core.LangFunctions;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.*;

public class PhpNumericStringTest extends JvmCompilerCase {
    @Test public void testNativePhp85NumericConversions() throws Throwable {
        String source = resource("php8_numeric_strings.php").substring(5);
        String[] expected = resource("php8_numeric_strings.expected").trim().split("\\r?\\n");
        for (boolean dumped : new boolean[] {false, true}) {
            ArrayMemory rows = run(source, PhpLanguageProfile.PHP_8_5, dumped).toValue(ArrayMemory.class);
            assertEquals(expected.length, rows.size());
            for (int i = 0; i < expected.length; i++) {
                String[] fields = expected[i].split("\t", 3);
                Memory row = rows.valueOfIndex(null, i), value = row.valueOfIndex(null, 1).toValue();
                assertEquals("label " + i, fields[0], row.valueOfIndex(null, 0).toString());
                if (fields[1].equals("integer")) {
                    assertEquals(fields[0], Memory.Type.INT, value.getRealType());
                    assertEquals(fields[0], Long.parseLong(fields[2]), value.toLong());
                } else if (fields[1].equals("double")) {
                    assertEquals(fields[0], Memory.Type.DOUBLE, value.getRealType());
                    double number = fields[2].equals("INF") ? Double.POSITIVE_INFINITY
                            : fields[2].equals("-INF") ? Double.NEGATIVE_INFINITY
                            : fields[2].equals("NaN") ? Double.NaN : Double.parseDouble(fields[2]);
                    if (Double.isNaN(number)) assertTrue(fields[0], Double.isNaN(value.toDouble()));
                    else assertEquals(fields[0], Double.doubleToRawLongBits(number), Double.doubleToRawLongBits(value.toDouble()));
                } else {
                    assertEquals(fields[0], fields[2], php.runtime.memory.output.serialization.PhpSerialization.serialize(
                            comparisonEnv, comparisonModule.getTrace(), value).toString());
                }
            }
            closeEnvironment();
        }
    }

    @Test public void testScannerLimitsAndFloatCastModulo() {
        assertFalse(PhpNumericString.isNumeric("42foo"));
        assertFalse(PhpNumericString.isNumeric("1e+"));
        assertTrue(PhpNumericString.isNumeric("\t1.e2\r"));
        assertEquals(1, PhpNumericString.parseLeading("1e+").toLong());
        assertEquals(1900, PhpNumericString.parseLeading("1.9e3foo").toLong());
        assertNull(PhpNumericString.parseStrict("\u000142"));
        String large = "0".repeat(40_000) + "1";
        assertTrue(PhpNumericString.isNumeric(large));
        assertEquals(1, PhpNumericString.parseStrict(large).toLong());
        assertEquals(Double.POSITIVE_INFINITY, PhpNumericString.parseStrict("9".repeat(40_000)).toDouble(), 0);
        Random random = new Random(80511);
        for (int i = 0; i < 6000; i++) {
            double value = Double.longBitsToDouble(random.nextLong());
            long expected = Double.isFinite(value) ? new BigDecimal(value).toBigInteger().longValue() : 0;
            assertEquals("float bits " + Long.toHexString(Double.doubleToRawLongBits(value)), expected,
                    PhpNumericConversions.floatToLong(value));
        }
    }

    @Test public void testLegacyProfileAndJavaAbi() throws Throwable {
        assertTrue(LangFunctions.is_numeric(StringMemory.valueOf("42foo")));
        assertEquals(1, LangFunctions.intval(StringMemory.valueOf("1e3")));
        for (boolean dumped : new boolean[] {false, true}) {
            assertTrue(run("return is_numeric('42foo') && is_numeric(true);", PhpLanguageProfile.JPHP_LEGACY, dumped).toBoolean());
            closeEnvironment();
            assertEquals(1, run("return (int)'1e3';", PhpLanguageProfile.JPHP_LEGACY, dumped).toLong());
            closeEnvironment();
        }
    }

    private Environment comparisonEnv;
    private ModuleEntity comparisonModule;
    @After public void closeEnvironment() throws Throwable {
        if (comparisonEnv != null) {
            Environment closing = comparisonEnv;
            comparisonEnv = null;
            closing.doFinal();
        }
    }
    private Memory run(String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        comparisonEnv = new Environment(newScope(), new ByteArrayOutputStream());
        comparisonEnv.getScope().registerExtension(new StandardExtension());
        Context context = new Context(source);
        context.setPhpLanguageProfile(profile);
        comparisonModule = new JvmCompiler(comparisonEnv, context).compile(false);
        if (dumped) {
            ModuleDumper dumper = new ModuleDumper(context, comparisonEnv, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            dumper.save(comparisonModule, bytes);
            comparisonModule = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        comparisonEnv.getScope().setPhpLanguageProfile(profile == PhpLanguageProfile.PHP_8_5
                ? PhpLanguageProfile.JPHP_LEGACY : PhpLanguageProfile.PHP_8_5);
        comparisonEnv.getScope().loadModule(comparisonModule);
        comparisonEnv.getScope().addUserModule(comparisonModule);
        comparisonEnv.registerModule(comparisonModule);
        StringWriter diagnostics = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(comparisonModule.getData()), comparisonEnv.getScope().getClassLoader(),
                false, new PrintWriter(diagnostics));
        assertEquals("", diagnostics.toString());
        return comparisonModule.include(comparisonEnv);
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/expressions/" + name)) {
            assertNotNull(name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
