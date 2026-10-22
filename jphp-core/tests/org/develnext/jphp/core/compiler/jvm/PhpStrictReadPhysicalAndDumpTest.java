package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.RuntimeClassLoader;
import php.runtime.loader.dump.*;
import php.runtime.reflection.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

/** Physical read authority and additive parameter-default wire kinds; no QA property dependencies. */
public class PhpStrictReadPhysicalAndDumpTest {
    private static CompileScope scope(boolean debug) {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());return scope;
    }
    @Test(timeout=20000) public void foreignHostCannotReuseGeneratedConstantOperandsBeforeReadOrPublication()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();var env=new Environment(scope(debug),out);env.setErrorFlags(-1);
            var context=new Context("function siteSource(){return E_STRICT;}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(phb) {var bytes=new ByteArrayOutputStream();var dump=new ModuleDumper(context,env,debug);dump.save(module,bytes);module=dump.load(new ByteArrayInputStream(bytes.toByteArray()));}
            env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);
            var function=module.findFunction("siteSource");Class<?> actual=function.getNativeMethod().getDeclaringClass();
            var loader=(RuntimeClassLoader)actual.getClassLoader();byte[] defined=loader.definedInputBytes(actual);assertNotNull(defined);assertTrue(loader.matchesDefinedBytecode(actual,defined));
            var model=new ClassNode();new ClassReader(defined).accept(model,0);var keys=new LinkedHashSet<String>();int helperCalls=0;
            for(var member:model.methods)for(var instruction:member.instructions) {
                if(instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String text && text.startsWith("\0jphp.constant.fetch\0"))keys.add(text);
                if(instruction instanceof MethodInsnNode call && call.owner.equals(PhpConstantReads.OWNER) && call.name.equals("fetch") && call.desc.equals(PhpConstantReads.DESCRIPTOR))helperCalls++;
            }
            assertEquals(1,helperCalls);assertEquals(1,keys.size());String key=keys.iterator().next();
            var field=actual.getDeclaredField("$TRC");field.setAccessible(true);var traces=(TraceInfo[])field.get(null);assertTrue(traces.length>0);
            var domain=new PhpOwnershipDomain();var authority=new PhpRuntimeOwnership.Authority(env,domain);Throwable primary=null;
            try {
                try(var execution=authority.enter();var frame=authority.frame()) {
                    int rejected=0;
                    for(TraceInfo trace:traces)if(trace!=null) {
                        Memory published=Memory.CONST_INT_5;
                        try {published=PhpConstantReads.fetch(env,"E_STRICT","e_strict",trace,true,key);fail("Foreign host reused generated read operands");}
                        catch(IllegalArgumentException expected) {assertTrue(expected.getMessage(),expected.getMessage().contains("exact defined caller and operands"));rejected++;}
                        assertSame(Memory.CONST_INT_5,published);assertEquals(0,domain.graph().liveNodes());
                    }
                    assertTrue(rejected>0);env.flushAll();assertEquals(0,out.size());
                }
            } catch(Throwable failure) {primary=failure;throw failure;}
            finally {
                Throwable cleanup=null;try {env.doFinal();}catch(Throwable failure){cleanup=failure;}
                try {authority.close();}catch(Throwable failure){if(cleanup==null)cleanup=failure;else if(cleanup!=failure)cleanup.addSuppressed(failure);}
                if(cleanup!=null){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}
            }
            assertEquals(0,domain.graph().liveNodes());
        }
    }
    private static byte[] save(ParameterDumper dumper,ParameterEntity parameter)throws IOException {
        var out=new ByteArrayOutputStream();dumper.save(parameter,out);return out.toByteArray();
    }
    private static int stamp(byte[] bytes) {
        int at=-1;for(int i=0;i<=bytes.length-4;i++)if(ByteBuffer.wrap(bytes).getInt(i)==0x5044494e){assertEquals(-1,at);at=i;}assertTrue(at>=4);return at;
    }
    @Test(timeout=20000) public void pdin2PromotionAndPdin3ConstantKindRoundTripAndRejectInvalidFlagsAndKind()throws Throwable {
        var env=new Environment(scope(false),new ByteArrayOutputStream());var context=new Context(" ");var dumper=new ParameterDumper(context,env,true);
        try {
            var legacy=new ParameterEntity(context);legacy.setName("legacy");legacy.setPromoted(true);
            legacy.setDefaultInitializer(new ParameterEntity.DefaultInitializer("module","Owner","__$parameterDefault$old","Decl"));
            byte[] old=save(dumper,legacy);assertEquals(2,ByteBuffer.wrap(old).getInt(stamp(old)+4));
            var restored=dumper.load(new ByteArrayInputStream(old));assertTrue(restored.isPromoted());assertEquals(legacy.getDefaultInitializer(),restored.getDefaultInitializer());assertFalse(restored.getDefaultInitializer().constantOnly());assertNull(restored.getDefaultValueConstName());
            var modern=new ParameterEntity(context);modern.setName("modern");modern.setPromoted(true);modern.setDefaultValueConstName("E_STRICT");
            modern.setDefaultInitializer(new ParameterEntity.DefaultInitializer("module","Owner","__$parameterDefault$constant$1","Decl",ParameterEntity.DefaultInitializer.Kind.CONSTANT_EXPRESSION));
            byte[] good=save(dumper,modern);int at=stamp(good);assertEquals(3,ByteBuffer.wrap(good).getInt(at+4));
            restored=dumper.load(new ByteArrayInputStream(good));assertTrue(restored.isPromoted());assertEquals(modern.getDefaultInitializer(),restored.getDefaultInitializer());assertTrue(restored.getDefaultInitializer().constantOnly());assertEquals("E_STRICT",restored.getDefaultValueConstName());assertTrue(restored.getDefaultValue().isNull());
            int typeLength=ByteBuffer.wrap(good).getInt(at+8);assertTrue(typeLength>=0);int flags=at+12+typeLength;assertEquals(1,good[flags]);assertEquals(1,good[flags+1]);
            byte[] badPromotion=good.clone();badPromotion[flags]=2;
            byte[] missing=good.clone();missing[flags+1]=0;
            byte[] badKind=good.clone();ByteBuffer.wrap(badKind).putInt(badKind.length-4,0);
            byte[] future=good.clone();ByteBuffer.wrap(future).putInt(at+4,4);
            for(byte[] invalid:new byte[][]{badPromotion,missing,badKind,future}) {
                try {dumper.load(new ByteArrayInputStream(invalid));fail("Malformed constant-default descriptor accepted");}
                catch(IOException expected) {assertNotNull(expected.getMessage());}
            }
        } finally {env.doFinal();}
    }
}
