package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.env.handler.ErrorReportHandler;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual native pinned PHP source, compiled and independently transported through PHB. */
public class PhpRawDynamicStringTest {
    private void group(String group) throws Throwable {
        var stream=getClass().getClassLoader().getResourceAsStream("resources/strings/php85_raw_dynamic.tsv");
        assertNotNull(stream);int count=0;
        for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
            String[] pin=line.split("\t",-1);if(!pin[0].equals(group))continue;count++;
            String source=new String(Base64.getDecoder().decode(pin[2]),StandardCharsets.UTF_8);
            for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
                String label=pin[1]+",debug="+debug+",PHB="+dumped;
                var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
                scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
                scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var warnings=new ArrayList<String>();
                env.setErrorReportHandler(new ErrorReportHandler(){
                    public boolean onError(SystemMessage message){warnings.add(message.getType().value+":"+message.getMessage());return true;}
                    public boolean onFatal(ErrorException failure){throw new AssertionError(failure);}
                });
                try {
                    var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module=new JvmCompiler(env,context).compile(false);
                    if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                    Memory result=module.include(env);env.flushAll();
                    assertEquals(label+",return",pin[3],HexFormat.of().formatHex(result.getBinaryBytes(StandardCharsets.UTF_8)));
                    assertEquals(label+",output",pin[4],HexFormat.of().formatHex(output.toByteArray()));
                    assertEquals(label+",warnings",new String(Base64.getDecoder().decode(pin[5]),StandardCharsets.UTF_8),String.join("\n",warnings));
                } finally { env.doFinal(); }
            }
        }
        assertTrue(group,count>0);
    }
    @Test public void nativeDynamicConcat() throws Throwable {group("concat");}
    @Test public void nativeEvaluationAndCoercionOrder() throws Throwable {group("ordering");}
    @Test public void nativeInterpolationAndHeredoc() throws Throwable {group("interpolation");}
    @Test public void nativeConcatAssignments() throws Throwable {group("assign");}
    @Test public void nativeUnionConsumers() throws Throwable {group("consumers");}
    @Test public void nativeStableConstantFolding() throws Throwable {group("fold");}
    @Test public void ordinaryInterpolationEmitsOneBuilderAndNoByteWrapper() throws Throwable {
        var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env=new Environment(scope,new ByteArrayOutputStream());
        try {
            var context=new Context("$s='text';return \"A{$s}Z\";");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(module.getData()).accept(node,0);
            int builders=0,byteWrappers=0,finishUnion=0;
            for(var method:node.methods)for(var instruction:method.instructions) {
                if(instruction instanceof org.objectweb.asm.tree.TypeInsnNode type && instruction.getOpcode()==org.objectweb.asm.Opcodes.NEW) {
                    if(type.desc.equals("java/lang/StringBuilder"))builders++;
                    if(type.desc.equals("php/runtime/PhpByteStringBuilder")||type.desc.equals("php/runtime/PhpByteStringMemory"))byteWrappers++;
                }
                if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals("php/runtime/PhpByteStringBuilders")&&call.name.equals("finishObject"))finishUnion++;
            }
            assertEquals(1,builders);assertEquals(0,byteWrappers);assertEquals(1,finishUnion);
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            assertEquals("AtextZ",module.include(env).toString());
        }finally{env.doFinal();}
    }
}
