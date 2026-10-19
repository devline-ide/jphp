package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Generator hook bodies bind only at their real resumed JVM member; native PHP controls stay exact. */
public class PhpPropertyHookGeneratorTest extends JvmCompilerCase {
    private byte[] resource(String name)throws IOException {
        try(var stream=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,stream);return stream.readAllBytes();}
    }
    private void cases(boolean positive)throws Throwable {
        var rows=new Gson().fromJson(new String(resource("resources/property_hooks/generator_resume/controls.json"),StandardCharsets.UTF_8),JsonArray.class);
        var failures=new ArrayList<Throwable>();int compared=0;
        for(var element:rows) {
            var row=element.getAsJsonObject();if((row.get("nativeExit").getAsInt()==0)!=positive)continue;
            for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
                if(!positive&&dump)continue; // Actual native compile rejection has no serializable artifact.
                compared++;String path=row.get("resource").getAsString(),label=row.get("id").getAsString()+" debug="+debug+" PHB="+dump;
                var scope=newScope();scope.setDebugMode(debug);var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
                Path directory=Files.createTempDirectory("jphp-hook-generator-"),file=directory.resolve("fixture.php");
                try {
                    byte[] code=resource(path+".php");Files.write(file,code);var context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module=new JvmCompiler(env,context).compile(false);
                    if(!positive)fail("Required native generator rejection admitted: "+label);
                    if(dump){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,true);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);module.include(env);env.flushAll();
                    assertArrayEquals(label,resource(path+".stdout"),output.toByteArray());assertEquals(label,0,resource(path+".stderr").length);
                }catch(Throwable failure) {
                    if(positive||failure instanceof AssertionError)failures.add(new AssertionError(label,failure));
                    else try {
                        assertEquals(label,row.get("message").getAsString(),failure.getMessage());
                        assertArrayEquals(label+" no body effects before rejection",resource(path+".stdout"),output.toByteArray());
                    }catch(Throwable mismatch){failures.add(new AssertionError(label,mismatch));}
                }finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
            }
        }
        assertEquals(positive?56:12,compared);
        if(!failures.isEmpty()){var error=new AssertionError(failures.size()+" generator hook variants failed");failures.forEach(error::addSuppressed);throw error;}
    }
    @Test public void resumedGettersKeepParentBackingReferenceAndFinallySemantics()throws Throwable{cases(true);}
    @Test public void forbiddenGeneratorHookFormsKeepNativeCompileRejections()throws Throwable{cases(false);}
    @Test public void physicalResumeEnvelopeRejectsForgedBindingBeforeAnyBodyEffects()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(int mutation=0;mutation<3;mutation++) {
            var scope=newScope();scope.setDebugMode(debug);var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context("class H{public $x{get{echo 'body';yield 2;}}}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);var runner=module.getGenerators().iterator().next();
                ClassNode physical=new ClassNode();new ClassReader(runner.getData()).accept(physical,0);boolean changed=false;
                for(MethodNode member:physical.methods)if(member.name.equals("_run"))for(AbstractInsnNode instruction:member.instructions)
                    if(instruction instanceof MethodInsnNode call&&call.name.equals("bindGeneratedGenerator")) {
                        if(mutation==0)call.name="bindGeneratedMethod";
                        else if(mutation==1)((LdcInsnNode)call.getPrevious()).cst="foreign_run";
                        else { // Exact receiver source is a required operand, independent of logical hook metadata.
                            AbstractInsnNode load=call.getPrevious().getPrevious().getPrevious().getPrevious().getPrevious();
                            assertTrue(load instanceof VarInsnNode);((VarInsnNode)load).var=1;
                        }
                        changed=true;
                    }
                assertTrue(changed);ClassWriter writer=new ClassWriter(0);physical.accept(writer);runner.setData(writer.toByteArray());
                try{new ModuleDumper(context,env,true).save(module,new ByteArrayOutputStream());fail("Forged resumed binding admitted");}
                catch(php.runtime.loader.dump.io.DumpException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("Generator hook lacks its actual resumed binding header"));}
                assertEquals(0,output.size());
            }finally{env.doFinal();}
        }
    }
    @Test public void logicalGeneratorMetadataCannotEnterAResumeHookFromHostJava()throws Throwable {
        var scope=newScope();var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
        try {
            var context=new Context("class H{public $x{get{echo 'body';yield 2;}}}return (new H)->x;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            var returned=module.include(env).toValue();assertTrue(returned instanceof php.runtime.memory.ObjectMemory);
            var runner=(php.runtime.lang.Generator)((php.runtime.memory.ObjectMemory)returned).value;
            try{php.runtime.PhpPropertyHookBodyAccess.bindGeneratedGenerator(runner,env,"x",1,"_run");fail("Host supplied logical generator admitted");}
            catch(IllegalArgumentException expected){assertEquals("Resumed hook binding has no actual generated body entry",expected.getMessage());}
            env.flushAll();assertEquals(0,output.size());
        }finally{env.doFinal();}
    }
}
