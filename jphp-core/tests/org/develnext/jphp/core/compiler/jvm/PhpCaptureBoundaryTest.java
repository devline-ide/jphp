package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Explicit use($value) warnings at creation, independent of arrow invocation semantics. */
public class PhpCaptureBoundaryTest {
    private static byte[] resource(String name)throws Exception {
        try(var input=PhpCaptureBoundaryTest.class.getClassLoader().getResourceAsStream("resources/native-oracle/capture-boundary/"+name)) {
            assertNotNull("Missing native capture resource "+name,input);return input.readAllBytes();
        }
    }
    @BeforeClass public static void origins()throws Exception {
        String runtime=System.getProperty("qa.runtime"),core=System.getProperty("qa.core");
        if(runtime!=null)assertEquals(Path.of(runtime).toRealPath(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        if(core!=null)assertEquals(Path.of(core).toRealPath(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static CompileScope scope(boolean debug) {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());return scope;
    }
    private static ModuleEntity compile(Environment env,String source,boolean phb)throws Throwable {
        if(source.startsWith("<?php"))source=source.substring(5);
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        ModuleEntity module=new JvmCompiler(env,context).compile(false);
        if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,env.scope.isDebugMode());codec.save(module,bytes);assertTrue(bytes.size()>0);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static void nativeCase(String name)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(scope(debug),output);
            var stack=env.getCallStack();var buffers=env.getOutputBuffers();env.getDefaultBuffer().setImplicitFlush(true);
            try {
                compile(env,new String(resource(name+".php"),StandardCharsets.UTF_8),phb).include(env);env.flushAll();
                byte[] actual=output.toByteArray(),expected=resource(name+".stdout");
                System.out.println("CELL "+name+" debug="+debug+" phb="+phb+" ACTUAL "+new String(actual,StandardCharsets.UTF_8));
                assertArrayEquals(name+"/"+debug+"/"+phb,expected,actual);assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());
            } finally {env.doFinal();}
        }
    }
    @Test(timeout=60000) public void defaultParametersReferenceAliasesAndArraySnapshots()throws Throwable {nativeCase("default-reference-snapshots");}
    @Test(timeout=60000) public void enclosingArrowsPropagateDefinedNestedCaptureSnapshots()throws Throwable {nativeCase("arrow-nested-explicit");}
    @Test(timeout=60000) public void discardedValueCapturesRetainCreationEffectsAndThrow()throws Throwable {nativeCase("discarded-value-capture");}
}
