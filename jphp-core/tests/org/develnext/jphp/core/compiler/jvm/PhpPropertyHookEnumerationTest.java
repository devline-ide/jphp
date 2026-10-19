package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

/** Actual native bytes, including lazy nested reads, visibility, reentry and getter exceptions. */
public class PhpPropertyHookEnumerationTest extends JvmCompilerCase {
    private byte[] resource(String path)throws IOException {
        try(var input=getClass().getClassLoader().getResourceAsStream(path)){assertNotNull(path,input);return input.readAllBytes();}
    }
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump)throws Throwable {
        return compile(env,source,dump,null);
    }
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump,File fixture)throws Throwable {
        var context=source.startsWith("<?php")?new Context(source,fixture):new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var output=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,true);codec.save(module,output);module=codec.load(new ByteArrayInputStream(output.toByteArray()));}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private void nativeCase(String name)throws Throwable {
        for(String strictness:new String[]{"weak","strict"})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            String path="resources/property_hooks/enumeration/"+strictness+"/"+name,label=path+" debug="+debug+" PHB="+dump;
            var scope=newScope();scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            Path directory=Files.createTempDirectory("jphp-hook-enumeration-"),file=directory.resolve(name+".php");
            try {
                byte[] code=resource(path+".php");Files.write(file,code);
                var module=compile(env,new String(code,StandardCharsets.UTF_8),dump,file.toFile());
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);module.include(env);env.flushAll();
                assertArrayEquals(label,resource(path+".stdout"),output.toByteArray());assertEquals(label,0,resource(path+".stderr").length);
            }finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
        }
    }
    @Test public void originalExportJsonVarsExactNativeBytes()throws Throwable{nativeCase("export_json_vars");}
    @Test public void nestedObjectsInvokeTheirGettersBeforeLaterOuterProperties()throws Throwable{nativeCase("enumeration_nested_lazy");}
    @Test public void visibilityReentryErrorsAndReferenceGetterRetainExactSemantics()throws Throwable{nativeCase("enumeration_visibility_reentry_errors");}
    @Test public void publicJsonElementSerializerUsesTheSameGetterView()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var module=compile(env,"class H {public int $x {get=>4;}protected int $hidden {get=>9;}}return new H;",dump);
                Memory value=module.include(env);var serializer=new org.develnext.jphp.json.gson.MemorySerializer();serializer.setEnv(env);
                assertEquals("{\"x\":4}",serializer.serialize(value,Memory.class,null).toString());
            }finally{env.doFinal();}
        }
    }
}
