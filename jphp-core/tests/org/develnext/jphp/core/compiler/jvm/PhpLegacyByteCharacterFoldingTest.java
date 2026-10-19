package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;

/** Values and physical calls, including after PHB restore; no timing-based assertions. */
public class PhpLegacyByteCharacterFoldingTest extends JvmCompilerCase {
    private static final String OWNER="php/runtime/ext/core/StringFunctions";
    private byte[] resource(String name) throws IOException {
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/legacy-byte-folding/"+name)) {
            assertNotNull(name,input);return input.readAllBytes();
        }
    }
    private static void collect(byte[] data,List<String> calls) {
        if(data==null || data.length==0)return;
        var clazz=new ClassNode();new ClassReader(data).accept(clazz,0);
        for(var method:clazz.methods)for(var insn:method.instructions)if(insn instanceof MethodInsnNode call && call.owner.equals(OWNER))
            calls.add(call.name+call.desc);
    }
    private static List<String> calls(ModuleEntity module) {
        var calls=new ArrayList<String>();collect(module.getData(),calls);
        for(var f:module.getFunctions())collect(f.getData(),calls);
        for(var c:module.getClasses())collect(c.getData(),calls);
        return calls;
    }
    private void run(String name,PhpLanguageProfile profile,String expected,int bytecodeControl) throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);
            env.getScope().setDebugMode(debug);env.getScope().setPhpLanguageProfile(profile);
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                var code=new String(resource(name+".php"),StandardCharsets.UTF_8);
                var context=new Context(code.substring(5));context.setPhpLanguageProfile(profile);
                var module=new JvmCompiler(env,context).compile(false);
                if(isCompiled()) {var dumper=new ModuleDumper(context,env,debug);var raw=new ByteArrayOutputStream();dumper.save(module,raw);module=dumper.load(new ByteArrayInputStream(raw.toByteArray()));}
                var calls=calls(module);
                if(bytecodeControl==1)assertTrue("Legacy literals must fold, got "+calls,calls.isEmpty());
                if(bytecodeControl==2) {
                    assertTrue(calls.toString(),calls.contains("ord(C)I"));assertTrue(calls.toString(),calls.contains("chr(I)Ljava/lang/String;"));
                    assertFalse(calls.toString(),calls.stream().anyMatch(c->c.startsWith("nativeOrd(")||c.startsWith("nativeChr(")));
                }
                if(bytecodeControl==3) {
                    assertTrue(calls.toString(),calls.stream().anyMatch(c->c.startsWith("nativeOrd(")));
                    assertTrue(calls.toString(),calls.stream().anyMatch(c->c.startsWith("nativeChr(")));
                    assertFalse(calls.toString(),calls.contains("ord(C)I"));assertFalse(calls.toString(),calls.contains("chr(I)Ljava/lang/String;"));
                }
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                byte[] bytes=expected==null?resource(name+".expected"):expected.getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(name+" debug="+debug+" PHB="+isCompiled()+" actual="+output.toString(StandardCharsets.UTF_8),bytes,output.toByteArray());
            }finally{env.doFinal();}
        }
    }
    @Test public void legacyLiteralCallsFoldWithoutNativeBody() throws Throwable {run("legacy-literal",PhpLanguageProfile.JPHP_LEGACY,"65|66|C",1);}
    @Test public void legacyVariableCallsUseHistoricalDescriptors() throws Throwable {run("legacy-variable",PhpLanguageProfile.JPHP_LEGACY,"65|B",2);}
    @Test public void legacyDirectAndDynamicValuesRetainUnicodeAndRawBytes() throws Throwable {run("legacy-values",PhpLanguageProfile.JPHP_LEGACY,"65535|65535|233|0|255",0);}
    @Test public void modernLiteralCallsKeepNativeWarningsAndByteValues() throws Throwable {run("modern-literal",PhpLanguageProfile.PHP_8_5,null,3);}
    @Test public void modernVariableCallsKeepByteWrappers() throws Throwable {run("modern-variable",PhpLanguageProfile.PHP_8_5,null,3);}
    @Test public void modernDynamicStrictCallsKeepPublishedBoundary() throws Throwable {run("modern-dynamic-strict",PhpLanguageProfile.PHP_8_5,null,0);}
}
