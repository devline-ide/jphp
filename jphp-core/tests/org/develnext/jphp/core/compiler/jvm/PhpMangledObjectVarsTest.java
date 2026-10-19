package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;
public class PhpMangledObjectVarsTest extends JvmCompilerCase {
    private byte[] resource(String name)throws IOException {
        try(var stream=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,stream);return stream.readAllBytes();}
    }
    @Test public void rawSnapshotAndSignatureMatchNativeWithoutCallingHooks()throws Throwable {
        for(String mode:new String[]{"weak","strict"})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            String path="resources/property_hooks/mangled/"+mode,label=mode+" debug="+debug+" PHB="+dump;
            var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var directory=Files.createTempDirectory("jphp-mangled-");var file=directory.resolve("fixture.php");
            try {
                byte[] code=resource(path+".php");Files.write(file,code);var context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);module.include(env);env.flushAll();
                assertArrayEquals(label,resource(path+".stdout"),output.toByteArray());assertEquals(label,0,resource(path+".stderr").length);
            }finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
        }
    }
}
