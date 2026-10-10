package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.DoubleMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PhpArithmeticTest extends JvmCompilerCase {
    @Test public void testPhp85Matrix() throws Throwable { oracle("php8_arithmetic_matrix"); }
    @Test public void testPhp85Contracts() throws Throwable { oracle("php8_arithmetic_contract"); }
    @Test public void testDoubleMemoryPowerJavaAbi() {
        assertEquals(Math.pow(2.5, 1.5), DoubleMemory.valueOf(2.5).pow(DoubleMemory.valueOf(1.5)).toDouble(), 0);
        assertEquals(Math.pow(2.5, 1.5), DoubleMemory.valueOf(2.5).pow(new ReferenceMemory(DoubleMemory.valueOf(1.5))).toDouble(), 0);
    }
    @Test public void testArithmeticProfileIsStoredInBytecode() throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                Context context = new Context("function signs($a,$b) { return [$a ** $b ** 2, -$a ** 2]; } "
                        + "return [2 ** 3 ** 2, -2 ** 2, signs(2,3)];");
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
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                Memory result = module.include(env);
                boolean modern = profile == PhpLanguageProfile.PHP_8_5;
                assertEquals(modern ? 512 : 64, result.valueOfIndex(null, 0).toLong());
                assertEquals(modern ? -4 : 4, result.valueOfIndex(null, 1).toLong());
                assertEquals(modern ? 512 : 64, result.valueOfIndex(null, 2).valueOfIndex(null, 0).toLong());
                assertEquals(modern ? -4 : 4, result.valueOfIndex(null, 2).valueOfIndex(null, 1).toLong());
            } finally { env.doFinal(); }
        }
    }

    private void oracle(String name) throws Throwable {
        String source = resource(name + ".php").substring(5);
        String[] expected = resource(name + ".expected").trim().split("\\r?\\n");
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().registerExtension(new StandardExtension());
            try {
                Context context = new Context(source);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                ModuleEntity module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    ModuleDumper dumper = new ModuleDumper(context, env, true);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);
                env.getScope().addUserModule(module);
                env.registerModule(module);
                var trace = php.runtime.env.TraceInfo.valueOf(context.getFileName(), 0, 0, module.getInternalName());
                StringWriter diagnostics = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(module.getData()), env.getScope().getClassLoader(), false, new PrintWriter(diagnostics));
                for (var function : module.getFunctions())
                    CheckClassAdapter.verify(new ClassReader(function.getData()), env.getScope().getClassLoader(), false, new PrintWriter(diagnostics));
                for (var clazz : module.getClasses())
                    CheckClassAdapter.verify(new ClassReader(clazz.getData()), env.getScope().getClassLoader(), false, new PrintWriter(diagnostics));
                assertEquals("", diagnostics.toString());
                ArrayMemory rows = module.include(env).toValue(ArrayMemory.class);
                assertEquals(expected.length, rows.size());
                for (int i = 0; i < expected.length; i++) {
                    String[] fields = expected[i].split("\t", 5);
                    Memory row = rows.valueOfIndex(null, i), value = row.valueOfIndex(null, 1).toValue();
                    assertEquals("label " + i, fields[0], row.valueOfIndex(null, 0).toString());
                    assertEquals(fields[0] + " error", fields[3], row.valueOfIndex(null, 2).toString());
                    assertEquals(fields[0] + " warnings", fields[4], php.runtime.memory.output.serialization.PhpSerialization.serialize(
                            env, trace, row.valueOfIndex(null, 3)).toString());
                    if (fields[1].equals("integer")) {
                        assertEquals(fields[0], Memory.Type.INT, value.type);
                        assertEquals(fields[0], Long.parseLong(fields[2]), value.toLong());
                    } else if (fields[1].equals("double")) {
                        assertEquals(fields[0], Memory.Type.DOUBLE, value.type);
                        double number = fields[2].equals("INF") ? Double.POSITIVE_INFINITY : fields[2].equals("-INF")
                                ? Double.NEGATIVE_INFINITY : fields[2].equals("NaN") ? Double.NaN : Double.parseDouble(fields[2]);
                        if (Double.isNaN(number)) assertTrue(fields[0], Double.isNaN(value.toDouble()));
                        else if (fields[0].endsWith(":**") && Double.isFinite(number) && number != 0)
                            assertEquals(fields[0], number, value.toDouble(), 2 * Math.ulp(number));
                        else assertEquals(fields[0], Double.doubleToRawLongBits(number), Double.doubleToRawLongBits(value.toDouble()));
                    } else assertEquals(fields[0], fields[2], php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, value).toString());
                }
            } finally { env.doFinal(); }
        }
    }
    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/expressions/" + name)) {
            assertNotNull(name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
