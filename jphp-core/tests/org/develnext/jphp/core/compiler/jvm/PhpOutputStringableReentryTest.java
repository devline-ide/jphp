package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.ext.support.Extension;
import php.runtime.lang.BaseObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** PHP magic conversion is reentrant; unknown host conversion and physical sinks remain guarded. */
public class PhpOutputStringableReentryTest {
    @BeforeClass public static void origins() throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static byte[] resource(String name)throws Exception {
        try(var in=PhpOutputStringableReentryTest.class.getClassLoader().getResourceAsStream("resources/native-oracle/output-stringable-reentry/"+name)){
            assertNotNull(name,in);return in.readAllBytes();
        }
    }
    private static CompileScope scope(PhpLanguageProfile profile,boolean debug) {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(profile);
        scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());return scope;
    }
    private static ModuleEntity compile(Environment env,String source,PhpLanguageProfile profile,boolean phb)throws Throwable {
        if(source.startsWith("<?php"))source=source.substring(5);
        var context=new Context(source);context.setPhpLanguageProfile(profile);
        ModuleEntity module=new JvmCompiler(env,context).compile(false);
        if(phb){var bytes=new ByteArrayOutputStream();var dump=new ModuleDumper(context,env,env.scope.isDebugMode());dump.save(module,bytes);module=dump.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static void nativeCase(String name)throws Throwable {
        for(var profile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5})
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            var output=new ByteArrayOutputStream();var env=new Environment(scope(profile,debug),output);env.getDefaultBuffer().setImplicitFlush(true);
            var stack=env.getCallStack();var buffers=env.getOutputBuffers();int stackTop=env.getCallStackTop(),bufferDepth=buffers.size();
            try {
                compile(env,new String(resource(name+".php"),StandardCharsets.UTF_8),profile,phb).include(env);env.flushAll();
                assertArrayEquals(name+"/"+profile+"/"+debug+"/"+phb,resource(name+".stdout"),output.toByteArray());
                assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());assertEquals(stackTop,env.getCallStackTop());assertEquals(bufferDepth,env.getOutputBuffers().size());
            } finally {env.doFinal();}
        }
    }
    @Test(timeout=60000) public void phpMagicPrintEchoReferenceArrayPropertyAndBufferOrder()throws Throwable {nativeCase("ordered");}
    @Test(timeout=60000) public void throwingPhpMagicPreservesNestedOutputAndRestoresLaterWrites()throws Throwable {nativeCase("throw");}

    @Reflection.Name("NativeOutputValue") @Reflection.NativePhpSignature
    public static class NativeOutputValue extends BaseObject {
        static int calls,parkRejected;
        public NativeOutputValue(Environment env,ClassEntity clazz){super(env,clazz);}
        @Reflection.Signature(result=@Reflection.Arg(type=HintType.STRING))
        public Memory __toString(Environment env,Memory...args)throws Throwable{
            calls++;
            try(var park=env.scope.getClassLoader().getCallbackDomain().parkForHostLifecycle()) {throw new AssertionError("Active conversion lost nonparkable domain lease");}
            catch(PhpOperationGate.OverlapException expected){parkRejected++;}
            env.echo("i");return StringMemory.valueOf("v");
        }
    }
    private static class NativeProvider extends Extension {
        @Override public String getName(){return "output-stringable-reentry-native-control";}
        @Override public Status getStatus(){return Status.EXPERIMENTAL;}
        @Override public void onRegister(CompileScope scope){registerClass(scope,NativeOutputValue.class);}
    }
    @Test(timeout=60000) public void registeredNativePhpMagicMayEchoButCannotParkRootAdmission()throws Throwable {
        for(var profile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5})
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            NativeOutputValue.calls=NativeOutputValue.parkRejected=0;
            var scope=scope(profile,debug);scope.registerExtension(new NativeProvider());var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setImplicitFlush(true);
            try {
                compile(env,"print(new NativeOutputValue);echo new NativeOutputValue;",profile,phb).include(env);env.flushAll();
                assertEquals("iviv",output.toString(StandardCharsets.UTF_8));assertEquals(2,NativeOutputValue.calls);assertEquals(2,NativeOutputValue.parkRejected);
            } finally {env.doFinal();}
        }
    }
    private interface Throwing {void run()throws Throwable;}
    private static void rejected(Throwing action)throws Throwable {
        try {action.run();fail("Host callback reentered root mutation");}
        catch(IllegalStateException expected){assertEquals("Root output mutation inside its active host callback",expected.getMessage());}
    }
    @Test(timeout=30000) public void unknownHostMemoryIObjectGettersAndPhysicalSinkStayRejected()throws Throwable {
        var output=new ByteArrayOutputStream();var env=new Environment(scope(PhpLanguageProfile.JPHP_LEGACY,false),output);env.getDefaultBuffer().setImplicitFlush(true);
        try {
            rejected(()->env.getDefaultBuffer().write(new StringMemory("x"){@Override public String toString(){env.echo("bad-memory");return "x";}}));
            var entity=new ClassEntity(new Context(""));entity.setName("HostGetter");
            var host=new BaseObject(env,entity){@Override public ClassEntity getReflection(){env.echo("bad-getter");return super.getReflection();}};
            rejected(()->env.getDefaultBuffer().write(new ObjectMemory(host)));
            var plain=new BaseObject(env,entity){};
            rejected(()->env.getDefaultBuffer().write(new ObjectMemory(plain){@Override public String toString(){env.echo("bad-object-subclass");return "x";}}));
            rejected(()->env.getDefaultBuffer().write(new StringMemory("x"){@Override public Memory toValue(){env.echo("bad-toValue");return this;}}));
            assertEquals("",output.toString(StandardCharsets.UTF_8));env.echo("ok");assertEquals("ok",output.toString(StandardCharsets.UTF_8));
        } finally {env.doFinal();}
        var bytes=new ByteArrayOutputStream();Environment[] actual=new Environment[1];int[] attempts={0};
        var sink=new OutputStream(){
            @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
            @Override public void write(byte[] values,int offset,int length)throws IOException{
                try {rejected(()->actual[0].echo(new ObjectMemory(new BaseObject(actual[0],new ClassEntity(new Context(""))){})));attempts[0]++;}
                catch(Throwable failure){throw new IOException(failure);}
                bytes.write(values,offset,length);
            }
        };
        actual[0]=new Environment(scope(PhpLanguageProfile.JPHP_LEGACY,false),sink);actual[0].getDefaultBuffer().setImplicitFlush(true);
        try {actual[0].echo("a");actual[0].echo("b");assertEquals("ab",bytes.toString(StandardCharsets.UTF_8));assertTrue(attempts[0]>=2);}
        finally {actual[0].doFinal();}
    }
    private static final class StructuralEnvironment extends Environment {
        String operation;
        int attempts;
        StructuralEnvironment(CompileScope scope,OutputStream output){super(scope,output);}
        private void hostMutation(String selected){
            if(selected.equals(operation)){attempts++;echo("bad-"+selected);}
        }
        @Override public CallStackItem pushCall(TraceInfo trace,php.runtime.lang.IObject self,Memory[] args,String function,String clazz,String staticClazz){
            hostMutation("push");return super.pushCall(trace,self,args,function,clazz,staticClazz);
        }
        @Override public TraceInfo trace(){hostMutation("trace");return super.trace();}
        @Override public CallStackItem popCall(){
            CallStackItem result=super.popCall();hostMutation("pop");return result;
        }
    }
    @Test(timeout=30000) public void structuralEnvironmentCallbacksCannotBorrowPhpMagicReentry()throws Throwable {
        for(String operation:new String[]{"environment","push","trace","pop"}){
            var scope=scope(PhpLanguageProfile.JPHP_LEGACY,false);scope.registerExtension(new NativeProvider());
            var output=new ByteArrayOutputStream();var env=new StructuralEnvironment(scope,output);env.getDefaultBuffer().setImplicitFlush(true);
            try {
                var entity=env.fetchClass(NativeOutputValue.class);assertNotNull(entity);assertNotNull(entity.methodMagicToString);
                NativeOutputValue.calls=NativeOutputValue.parkRejected=0;
                NativeOutputValue object=operation.equals("environment")?new NativeOutputValue(env,entity){
                    @Override public Environment getEnvironment(){env.attempts++;env.echo("bad-environment");return super.getEnvironment();}
                }:new NativeOutputValue(env,entity);
                env.operation=operation;
                rejected(()->env.getDefaultBuffer().write(new ObjectMemory(object)));
                assertEquals(operation,1,env.attempts);assertEquals(operation,0,env.getCallStackTop());
                assertEquals(operation,operation.equals("pop")?1:0,NativeOutputValue.calls);
                assertEquals(operation,operation.equals("pop")?"i":"",output.toString(StandardCharsets.UTF_8));
                env.operation=null;env.echo("z");
                assertEquals(operation,operation.equals("pop")?"iz":"z",output.toString(StandardCharsets.UTF_8));
                try(var restored=scope.getClassLoader().getCallbackDomain().parkForHostLifecycle()){}
            } finally {env.operation=null;env.doFinal();}
        }
    }
}
