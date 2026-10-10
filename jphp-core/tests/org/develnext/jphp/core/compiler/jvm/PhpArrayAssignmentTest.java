package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.ModuleDumper;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class PhpArrayAssignmentTest extends JvmCompilerCase {
    @Test public void testNativeWriteOrder() throws Throwable { oracle("php85_array_assignment"); }
    @Test public void testNativeSendOrder() throws Throwable { oracle("php85_call_send"); }
    @Test public void testNativeMethodSendOrder() throws Throwable { oracle("php85_method_send"); }
    @Test public void testNativeCallableScope() throws Throwable { oracle("php85_callable_scope"); }
    @Test public void testNativeClassConstants() throws Throwable { oracle("php85_class_constants"); }
    @Test public void testNativeLazyConstants() throws Throwable { oracle("php85_lazy_constants"); }
    @Test public void testNativeTypedConstantRuntime() throws Throwable { oracle("php85_typed_constant_runtime"); oracle("php85_typed_constant_namespace"); }
    @Test public void testNativeConstantClosures() throws Throwable { oracle("php85_constant_closures"); }
    @Test public void testNativeTraitConstants() throws Throwable { oracle("php85_trait_constants"); }
    @Test public void testNativeDynamicConstants() throws Throwable { oracle("php85_dynamic_constants"); }
    @Test public void testLegacyClassConstantCache() throws Throwable { oracle("legacy_class_constants", PhpLanguageProfile.JPHP_LEGACY); }
    @Test public void testNativeDiscardedExpressions() throws Throwable { oracle("php85_discarded_expressions"); }
    @Test public void testSdkMethodsAndConstructors() throws Throwable { oracle("php85_method_sdk"); }
    @Test public void testLegacyNamedConstructor() throws Throwable { oracle("legacy_named_constructor", PhpLanguageProfile.JPHP_LEGACY); }
    @Test public void testNativeCallArguments() throws Throwable { oracle("php85_call_arguments_weak"); oracle("php85_call_arguments_strict"); }
    @Test public void testNativeIndirectCalls() throws Throwable { oracle("php85_indirect_calls"); }
    @Test public void testNativeReferenceBinding() throws Throwable { oracle("php85_reference_binding"); }
    @Test public void testNativeAppendCursor() throws Throwable { oracle("php85_array_cursor"); }

    @Test public void testLegacyNegativeAppend() {
        var array = new php.runtime.memory.ArrayMemory();
        array.put(php.runtime.memory.LongMemory.valueOf(-5), php.runtime.memory.LongMemory.valueOf(1));
        array.add(php.runtime.memory.LongMemory.valueOf(2));
        assertEquals(2, array.valueOfIndex(0).toLong());
        assertTrue(array.valueOfIndex(-4).isUndefined());
    }

    private void oracle(String fixture) throws Throwable { oracle(fixture, PhpLanguageProfile.PHP_8_5); }

    private void oracle(String fixture, PhpLanguageProfile profile) throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().registerExtension(new StandardExtension());
            try {
                Context context = new Context(resource(fixture + ".php").substring(5));
                context.setPhpLanguageProfile(profile);
                JvmCompiler compiler;
                try { compiler = new JvmCompiler(env, context); }
                catch (php.runtime.exceptions.CustomErrorException error) {
                    throw new AssertionError(fixture + ":" + (error.getTraceInfo().getStartLine() + 1)
                            + ":" + (error.getTraceInfo().getStartPosition() + 1), error);
                }
                var module = compiler.compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);
                    var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                var diagnostics = new StringWriter();
                var writer = new PrintWriter(diagnostics);
                var loader = env.getScope().getClassLoader();
                CheckClassAdapter.verify(new ClassReader(module.getData()), loader, false, writer);
                for (var clazz : module.getClasses()) if (clazz.getData() != null) CheckClassAdapter.verify(new ClassReader(clazz.getData()), loader, false, writer);
                for (var closure : module.getClosures()) CheckClassAdapter.verify(new ClassReader(closure.getData()), loader, false, writer);
                assertEquals("", diagnostics.toString());
                php.runtime.Memory value;
                try { value = module.include(env); }
                catch (php.runtime.ext.java.JavaException error) { throw error.getThrowable(); }
                var trace = TraceInfo.valueOf(context.getFileName(), 0, 0, module.getInternalName());
                String actual = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, value).toString();
                var expectedRows = php.runtime.memory.output.serialization.PhpSerialization.unserialize(env, trace,
                        new php.runtime.memory.StringMemory(resource(fixture + ".expected")));
                assertEquals(expectedRows.toValue(php.runtime.memory.ArrayMemory.class).size(), value.toValue(php.runtime.memory.ArrayMemory.class).size());
                var mismatches = new java.util.ArrayList<String>();
                for (int i = 0; i < value.toValue(php.runtime.memory.ArrayMemory.class).size(); i++) {
                    String expectedRow = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, expectedRows.valueOfIndex(i)).toString();
                    String actualRow = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, value.valueOfIndex(i)).toString();
                    if (!expectedRow.equals(actualRow)) mismatches.add("row=" + i + "\nexpected=" + expectedRow + "\nactual=" + actualRow);
                }
                assertEquals("dumped=" + dumped + "\n" + String.join("\n", mismatches), 0, mismatches.size());
                assertEquals("dumped=" + dumped, resource(fixture + ".expected"), actual);
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
