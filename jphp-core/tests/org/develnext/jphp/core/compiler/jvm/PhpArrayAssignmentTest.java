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
    @Test public void testNativeUnsetAdvancesCurrentCursorAcrossPlainCowAndAliases() throws Throwable {
        String raw=resource("php85_cursor_unset.php");assertTrue(raw.startsWith("<?php"));
        byte[] expected=resource("php85_cursor_unset.stdout").getBytes(StandardCharsets.UTF_8);
        assertEquals("native stderr","",resource("php85_cursor_unset.stderr"));
        assertEquals("complete native cursor rows",12,new String(expected,StandardCharsets.UTF_8).lines().count());
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
            try {
                var context=new Context(raw.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped) {
                    var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);
                    assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());
                }
                assertEquals(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
                assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("native cursor unset debug="+debug+" PHB="+dumped,expected,output.toByteArray());
            }catch(Throwable failure){primary=failure;throw failure;}
            finally {
                try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}
            }
        }
    }

    private byte[] oldCursorWireResource(String name) throws IOException {
        try(InputStream in=getClass().getClassLoader().getResourceAsStream("resources/expressions/"+name)) {
            assertNotNull(name,in);return in.readAllBytes();
        }
    }

    @Test public void testActualAbi27LegacyAndModernCursorAssignmentsKeepWireProfile() throws Throwable {
        String[] files={"legacy27_cursor_assignment.phb","modern27_cursor_assignment.phb"};
        String[] outputs={"legacy27_cursor_assignment.stdout","php85_cursor_unset.stdout"};
        PhpLanguageProfile[] profiles={PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5};
        for(int i=0;i<files.length;i++) {
            String label="immutable ABI27 wire="+files[i]+",profile="+profiles[i];
            byte[] wire=oldCursorWireResource(files[i]);byte[] expected=oldCursorWireResource(outputs[i]);
            var header=ModuleDumper.readHeader(new ByteArrayInputStream(wire));
            assertEquals(label,27,header.getCompilerAbiVersion());assertEquals(label,profiles[i],header.getPhpLanguageProfile());
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.setDebugMode(false);
            scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
            try {
                var context=new Context("");context.setPhpLanguageProfile(profiles[i]);
                var module=new ModuleDumper(context,env,false).load(new ByteArrayInputStream(wire));
                assertTrue(label,module.isCompiledArtifact());assertEquals(label,profiles[i],module.getPhpLanguageProfile());
                assertEquals(label,27,module.getCompilerProvenance().getCompilerAbiVersion());
                byte[] original=module.getData().clone();
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                module.includeNoThrow(env,env.getGlobals());env.flushAll();
                assertArrayEquals(label,expected,output.toByteArray());
                assertArrayEquals(label+" original transport bytecode",original,module.getData());
                var actual=module.getNativeMethod().getDeclaringClass();
                assertTrue(label+" exact actual JVM definition/input witness",scope.getClassLoader().matchesDefinedBytecode(actual,original));
                assertEquals(label,PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
                assertEquals(label,profiles[i],module.getPhpLanguageProfile());
            }catch(Throwable failure){primary=failure;throw failure;}
            finally {
                try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}
                scope.getClassLoader().close();
            }
        }
    }

    @Test public void testModernResetLeavesNextOnSuccessorAcrossCopiesAndAliases() throws Throwable {
        String raw=resource("php85_reset_next.php");assertTrue(raw.startsWith("<?php"));
        byte[] expected=oldCursorWireResource("php85_reset_next.stdout");
        assertEquals("native stderr","",resource("php85_reset_next.stderr"));
        assertEquals("complete reset rows",7,new String(expected,StandardCharsets.UTF_8).lines().count());
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
            try {
                var context=new Context(raw.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped) {
                    var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("reset/next native debug="+debug+" PHB="+dumped,expected,output.toByteArray());
                assertEquals(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
                assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
            }catch(Throwable failure){primary=failure;throw failure;}
            finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
        }
    }

    @Test public void testModernNativePointerPrevEndAndMutationMatchesActualPhp85() throws Throwable {
        String raw=resource("php85_native_pointer.php");assertTrue(raw.startsWith("<?php"));
        byte[] expected=oldCursorWireResource("php85_native_pointer.stdout");
        assertEquals("native stderr","",resource("php85_native_pointer.stderr"));
        assertEquals("complete native pointer rows",10,new String(expected,StandardCharsets.UTF_8).lines().count());
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
            try {
                var context=new Context(raw.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped) {
                    var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("native pointer debug="+debug+" PHB="+dumped,expected,output.toByteArray());
                assertEquals(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
                assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
            }catch(Throwable failure){primary=failure;throw failure;}
            finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
        }
    }

    @Test public void testArrayKeyEndpointsDoNotMoveNativePointerAndMatchPhp85() throws Throwable {
        String qaTestClasses=System.getProperty("qa.endpoint.test.classes");
        if(qaTestClasses!=null)assertEquals("actual endpoint test class origin", java.nio.file.Path.of(qaTestClasses).toRealPath(), java.nio.file.Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        String raw=resource("php85_array_key_endpoints.php");assertTrue(raw.startsWith("<?php"));
        byte[] expected=oldCursorWireResource("php85_array_key_endpoints.stdout");
        assertEquals("native stderr","",resource("php85_array_key_endpoints.stderr"));
        assertEquals("complete array-key endpoint rows",38,new String(expected,StandardCharsets.UTF_8).lines().count());
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
            try {
                var context=new Context(raw.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped) {
                    var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("array-key endpoints debug="+debug+" PHB="+dumped,expected,output.toByteArray());
                System.out.println("ARRAY_KEY_ENDPOINT_NATIVE38_BYTES debug="+debug+" phb="+dumped);
                assertEquals(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
                assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
            }catch(Throwable failure){primary=failure;throw failure;}
            finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
        }
    }
}
