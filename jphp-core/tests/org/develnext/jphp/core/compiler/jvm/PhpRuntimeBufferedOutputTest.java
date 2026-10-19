package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Actual generated output with an exact callback-free buffer; no user coercion is admitted. */
public class PhpRuntimeBufferedOutputTest {
    private static final class Fixture implements AutoCloseable {
        final Environment env;
        final php.runtime.reflection.ModuleEntity module;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority;
        final PhpOwnershipDomain.Execution execution;
        Fixture(boolean debug,boolean dump,String source,boolean ordinary,boolean customEnv) throws Throwable {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();env=customEnv?new Environment(scope,output){}:new Environment(scope,output);
            env.getDefaultBuffer().setChunkSize(0);
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var entity=new JvmCompiler(env,context).compile(false);
            if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(entity,bytes);entity=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=entity;scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            var diagnostics=new StringWriter();CheckClassAdapter.verify(new ClassReader(module.getData()),scope.getClassLoader(),false,new PrintWriter(diagnostics));assertEquals("ASM", "",diagnostics.toString());
            authority=ordinary?new PhpRuntimeOwnership.Authority(env,domain):null;execution=ordinary?authority.enter():null;
        }
        void register() throws Throwable {authority.registerModule(module,module.getData(),(plan,member,bytes)->assertTrue(plan.requiredCapabilities().contains("literal-buffered-output")));}
        String text() throws Throwable{return env.getDefaultBuffer().getContents().toString();}
        @Override public void close()throws Exception {
            env.getDefaultBuffer().setCallback(null,null);env.getDefaultBuffer().setImplicitFlush(false);
            try {if(authority!=null)authority.close();}
            finally {if(execution!=null)execution.close();try {env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
        }
    }
    private static void bufferMode(Fixture fixture,String mode)throws Exception {
        if(mode.equals("callback"))fixture.env.getDefaultBuffer().setCallback(new StringMemory("unknown_callback"),null);
        else if(mode.equals("implicit"))fixture.env.getDefaultBuffer().setImplicitFlush(true);
        else if(mode.equals("chunk"))fixture.env.getDefaultBuffer().setChunkSize(1);
    }
    private static void rejected(Throwing action)throws Throwable {try {action.run();fail("Expected exact output admission rejection");}catch(IllegalArgumentException expected){}}
    private interface Throwing {void run()throws Throwable;}

    @Test public void generatedLiteralAndScalarCvOutputSourceDumpAndDebug()throws Throwable {
        String source="$a=5;echo 'L:',null,false,true,2,2.5,':',$a;$a=9;echo ':',$a;return 7;";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,source,true,false)) {
            f.register();try(var result=f.authority.include(f.module)){assertEquals(7,result.raw().toLong());}
            assertEquals("L:122.5:5:9",f.text());
            var node=new ClassNode();new ClassReader(f.module.getData()).accept(node,0);boolean helper=false;
            for(var method:node.methods)for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("php/runtime/PhpOwnershipNativeCalls")&&call.name.equals("echoRuntime"))helper=true;
            assertTrue("Actual scalar frame output helper",helper);
        }
    }
    @Test public void generatedCellPayloadReadsKeepCanonicalOwnersUnchanged()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"echo $a,',',$b;return 7;",true,false)) {
            f.authority.assignGlobal("a",LongMemory.valueOf(5));var cell=f.authority.globalAddress("a").getRuntimeHolder().reference();
            f.authority.globalAddress("b").getRuntimeHolder().rebind((ReferenceMemory)cell);f.authority.assignGlobal("a",LongMemory.valueOf(9));
            var origin=((ReferenceMemory)cell).getRuntimeCell();long before=origin.node().incoming();assertEquals(2,before);
            f.register();try(var result=f.authority.include(f.module)){assertEquals(7,result.raw().toLong());}
            assertEquals("9,9",f.text());assertEquals(before,origin.node().incoming());assertEquals(9,((ReferenceMemory)cell).getRawValue().toLong());
        }
    }
    @Test public void actualNativeResultOutputHasPopConsumerAndRetiresItsPacket()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"$a=5;echo serialize($a);return 7;",true,false)) {
            var member=php.runtime.memory.output.serialization.PhpSerialization.class.getMethod("serialize",Environment.class,TraceInfo.class,Memory.class);
            f.authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                public void validateArguments(java.util.List<PhpRuntimeOwnership.Value> arguments){assertEquals(1,arguments.size());assertEquals(5,arguments.getFirst().raw().toLong());}
            });
            var actual=f.module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(f.module.getData(),actual.getName(),PhpOwnershipExecution.descriptor(actual));
            assertTrue("Actual native RESULT retires after output",plan.producers().stream().anyMatch(producer->producer.kind().equals("NATIVE_RESULT")&&producer.consumers().contains("POP")));
            f.register();try(var result=f.authority.include(f.module)){assertEquals(7,result.raw().toLong());}
            assertEquals("i:5;",f.text());assertNull(PhpRuntimeOwnership.currentNativeBody(f.env));assertEquals(0,f.domain.graph().liveNodes());
        }
    }

    @Test public void outputHostRejectionPrecedesRegistrationAndEveryBody()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})for(String mode:new String[]{"callback","chunk","implicit","customEnv"}) {
            try(var f=new Fixture(debug,dump,"$marker=1;echo 'x';return 7;",true,mode.equals("customEnv"))) {
                bufferMode(f,mode);var protocol=new AtomicInteger();
                rejected(()->f.authority.registerModule(f.module,f.module.getData(),(plan,member,bytes)->protocol.incrementAndGet()));
                assertEquals("Admission before host protocol",0,protocol.get());assertNull("No PHP assignment before rejection",f.env.getGlobals().getByScalar("marker"));assertEquals("",f.text());
            }
            if(!mode.equals("customEnv"))try(var f=new Fixture(debug,dump,"$marker=1;echo 'x';return 7;",true,false)) {
                f.register();bufferMode(f,mode);rejected(()->{try(var result=f.authority.include(f.module)){fail("Body must not execute");}});
                assertNull("Target revalidation before first PHP store",f.env.getGlobals().getByScalar("marker"));assertEquals("",f.text());
            }
        }
    }
    @Test public void everyWriteRejectsHeapAndForgedScalarWithoutCoercionAndOffKeepsOutput()throws Throwable {
        try(var f=new Fixture(false,false,"return 7;",true,false);var frame=f.authority.frame()) {
            AtomicInteger coercions=new AtomicInteger();Memory forged=new StringMemory("value"){@Override public String toString(){coercions.incrementAndGet();return "value";}};
            rejected(()->PhpOwnershipNativeCalls.echoRuntime(frame,forged,f.env));assertEquals(0,coercions.get());assertEquals("",f.text());
            rejected(()->PhpOwnershipNativeCalls.echoRuntime(frame,new ArrayMemory(),f.env));assertEquals("",f.text());
            rejected(()->PhpOwnershipNativeCalls.echoRuntime(frame,new ObjectMemory(new php.runtime.lang.StdClass(f.env)),f.env));assertEquals("",f.text());
            f.env.getDefaultBuffer().setImplicitFlush(true);rejected(()->PhpOwnershipNativeCalls.echoLiteralRuntime(frame,"unsafe",f.env));assertEquals("",f.text());
        }
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"$a=5;echo 'off:',$a;return 7;",false,false)) {
            assertEquals(7,f.module.include(f.env).toLong());assertEquals("off:5",f.text());
        }
    }
}
