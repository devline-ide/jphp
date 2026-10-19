package php.runtime;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.output.serialization.PhpSerialization;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PhpOwnershipNativeCallsTest extends JvmCompilerCase {
    private String resource(String suffix) throws IOException {
        try (InputStream input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_native_handler_stack."+suffix)) {
            assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test public void nativeHandlerStackSuspensionAndThrownCallbackSourceDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context(resource("php").substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dump) {var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                module.include(env);env.getDefaultBuffer().flush();
                assertEquals("debug="+debug+" dump="+dump,resource("expected"),output.toString(StandardCharsets.UTF_8));
                assertNull(env.getErrorHandler());assertNull(env.getPreviousErrorHandler());
            } finally {env.doFinal();}
        }
    }
    @Test public void actualRegisteredArrayReadersBorrowWithoutStickyOwnerOrCow() throws Throwable {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env=new Environment(scope,new ByteArrayOutputStream());
        var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
        var session=new PhpOwnershipExecution.Session(participant,env);
        try (var execution=session.enter();var allocation=session.arrays().allocate(false)) {
            allocation.append(session.value(Memory.CONST_INT_1));
            var identity=allocation.array().getStorageIdentity();var node=allocation.binding().node();
            assertEquals(1,node.incoming());
            for (String name:new String[]{"serialize","count"}) {
                var function=env.fetchFunction(name);assertNotNull(function);
                try (var call=session.newCallRoots()) {
                    call.send(allocation.binding());assertEquals(2,node.incoming());
                    Memory result=call.invokeFunction(function,TraceInfo.UNKNOWN);
                    assertSame(result,call.result().rawValue());assertNull(call.result().node());
                    assertEquals(name.equals("count") ? "1" : "a:1:{i:0;i:1;}",result.toString());
                    assertSame(identity,allocation.array().getStorageIdentity());assertEquals(2,node.incoming());
                }
                assertEquals(1,node.incoming());assertEquals(1,allocation.array().size());
            }
        } finally {try {env.doFinal();} finally {participant.close();}}
    }

    @Test public void exactNativeTicketAndValueOriginRejectBeforeNativeBody() throws Throwable {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env=new Environment(scope,new ByteArrayOutputStream());
        var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
        var session=new PhpOwnershipExecution.Session(participant,env);
        try (var execution=session.enter()) {
            var function=env.fetchFunction("error_reporting");assertNotNull(function);
            int before=env.getErrorFlags();
            assertThrows(IllegalArgumentException.class,()->function.invoke(env,TraceInfo.UNKNOWN,new Memory[]{Memory.CONST_INT_1}));
            assertEquals(before,env.getErrorFlags());
            assertThrows(IllegalArgumentException.class,()->session.value(new php.runtime.memory.ArrayMemory()));
            try (var call=session.newCallRoots()) {
                call.send(session.value(Memory.CONST_INT_1));
                Memory previous=call.invokeFunction(function,TraceInfo.UNKNOWN);
                assertEquals(before,previous.toInteger());assertEquals(1,env.getErrorFlags());
                assertSame(previous,call.result().rawValue());
            }
            env.setErrorFlags(before);
        } finally {try {env.doFinal();} finally {participant.close();}}
    }

    @Test public void pureLiteralBoundaryRejectsFlushAndCallbackBeforeAppend() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var buffer=env.getDefaultBuffer();buffer.setChunkSize(0);
            PhpOwnershipNativeCalls.requireLiteralBufferedOutput(env);
            int before=buffer.getBufferSize();
            buffer.setChunkSize(1);
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipNativeCalls.requireLiteralBufferedOutput(env));
            assertEquals(before,buffer.getBufferSize());
            buffer.setChunkSize(0);buffer.setImplicitFlush(true);
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipNativeCalls.requireLiteralBufferedOutput(env));
            buffer.setImplicitFlush(false);buffer.setCallback(Memory.FALSE,null);
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipNativeCalls.requireLiteralBufferedOutput(env));
            buffer.setCallback(null,null);assertEquals(before,buffer.getBufferSize());
            assertThrows(NullPointerException.class,()->PhpOwnershipNativeCalls.echoLiteral(env,"unsafe"));
            assertEquals(before,buffer.getBufferSize());
        } finally {env.doFinal();}
    }

    private void admitMethod(PhpOwnershipExecution.Session session,php.runtime.reflection.ClassEntity clazz,String name) throws Throwable {
        var method=clazz.findMethod(name);assertNotNull(method);var member=method.getNativeMethod();
        var plan=org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.read(clazz.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
        assertNotNull(plan);assertEquals(plan.unsupported().toString(),java.util.Collections.emptyList(),plan.unsupported());
        session.registerGenerated(method,member,clazz.getData(),plan.runtimePlan(),(expected,actual,bytes)-> {
            var decoded=org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.read(bytes,actual.getName(),PhpOwnershipExecution.descriptor(actual));
            if (!plan.equals(decoded)) throw new IllegalArgumentException("Callback plan differs from exact generated body");
        });
    }

    @Test public void registrationOwnsActualCallableArrayAndReceiverUntilRestoreSourceDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            var context=new Context("class NativeOwnedHandler {function handle($n,$message){return true;} function __destruct(){echo 'destroy;';}}");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if (dumped) {var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            var domain=new PhpOwnershipDomain();var participant=domain.participant(env);var session=new PhpOwnershipExecution.Session(participant,env);
            try (var execution=session.enter()) {
                var clazz=env.fetchClass("NativeOwnedHandler",true,PhpLanguageProfile.PHP_8_5);
                admitMethod(session,clazz,"handle");admitMethod(session,clazz,"__destruct");
                var provider=session.objects().registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,php.runtime.reflection.ClassEntity.class),(e,bytes)->{});
                var object=session.objects().allocate(provider,env,java.util.Collections.emptyMap());
                var callable=session.arrays().allocate(false);
                callable.append(object.binding());callable.append(session.value(new php.runtime.memory.StringMemory("handle")));
                var objectNode=object.binding().node();var callableNode=callable.binding().node();
                try (var call=session.newCallRoots()) {call.send(callable.binding());assertSame(Memory.NULL,call.invokeFunction(env.fetchFunction("set_error_handler"),TraceInfo.UNKNOWN));}
                assertEquals(2,callableNode.incoming());
                var record=env.getErrorHandler();
                assertThrows(IllegalArgumentException.class,()->env.setErrorHandler(record));assertSame(record,env.getErrorHandler());
                object.close();callable.close();assertEquals(1,callableNode.incoming());assertEquals(1,objectNode.incoming());
                try (var call=session.newCallRoots()) {call.send(session.value(new php.runtime.memory.StringMemory("ping")));assertSame(Memory.TRUE,call.invokeFunction(env.fetchFunction("trigger_error"),TraceInfo.UNKNOWN));}
                assertEquals(0,env.getDefaultBuffer().getBufferSize());assertSame(record,env.getErrorHandler());
                try (var call=session.newCallRoots()) {assertSame(Memory.TRUE,call.invokeFunction(env.fetchFunction("restore_error_handler"),TraceInfo.UNKNOWN));}
                assertNull(env.getErrorHandler());assertEquals(PhpOwnership.State.DISPOSED,objectNode.state());assertEquals(PhpOwnership.State.DISPOSED,callableNode.state());
                env.getDefaultBuffer().flush();assertEquals("debug="+debug+" dump="+dumped,"destroy;",output.toString(StandardCharsets.UTF_8));
            } finally {try {env.doFinal();} finally {participant.close();}}
        }
    }

    @Test public void retainedRegistrationRetiresBeforeFinalFlushFailure() throws Throwable {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var sinkFailure=new IOException("host-output-failure");
        var env=new Environment(scope,new OutputStream(){@Override public void write(int value)throws IOException{throw sinkFailure;}});
        env.getDefaultBuffer().setChunkSize(0);
        var context=new Context("class NativeFinalHandler {function handle($n,$message){return true;} function __destruct(){echo 'destroy;';}}");
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
        scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
        var domain=new PhpOwnershipDomain();var participant=domain.participant(env);var session=new PhpOwnershipExecution.Session(participant,env);
        try (var execution=session.enter()) {
            var clazz=env.fetchClass("NativeFinalHandler",true,PhpLanguageProfile.PHP_8_5);
            admitMethod(session,clazz,"handle");admitMethod(session,clazz,"__destruct");
            var provider=session.objects().registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,php.runtime.reflection.ClassEntity.class),(e,bytes)->{});
            var object=session.objects().allocate(provider,env,java.util.Collections.emptyMap());var objectNode=object.binding().node();
            var callable=session.arrays().allocate(false);callable.append(object.binding());callable.append(session.value(new php.runtime.memory.StringMemory("handle")));
            var callableNode=callable.binding().node();
            try (var call=session.newCallRoots()) {call.send(callable.binding());call.invokeFunction(env.fetchFunction("set_error_handler"),TraceInfo.UNKNOWN);}
            object.close();callable.close();assertEquals(1,callableNode.incoming());
            assertSame(sinkFailure,assertThrows(IOException.class,env::doFinal));
            assertNull(env.getErrorHandler());assertNull(env.getPreviousErrorHandler());
            assertEquals(PhpOwnership.State.DISPOSED,objectNode.state());assertEquals(PhpOwnership.State.DISPOSED,callableNode.state());
            env.doFinal();
        } finally {participant.close();}
    }

    @Test public void previousRegistrationKeepsActualCallableUntilItsOwnRestore() throws Throwable {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
        var context=new Context("class NativeStackHandler {function handle($n,$message){return true;} function __destruct(){echo 'destroy;';}}");
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
        scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
        var domain=new PhpOwnershipDomain();var participant=domain.participant(env);var session=new PhpOwnershipExecution.Session(participant,env);
        try (var execution=session.enter()) {
            var clazz=env.fetchClass("NativeStackHandler",true,PhpLanguageProfile.PHP_8_5);
            admitMethod(session,clazz,"handle");admitMethod(session,clazz,"__destruct");
            var provider=session.objects().registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,php.runtime.reflection.ClassEntity.class),(e,bytes)->{});
            var nodes=new java.util.ArrayList<PhpOwnership.Node>();var records=new java.util.ArrayList<php.runtime.env.handler.ErrorHandler>();
            for (int i=0;i<2;i++) {
                var object=session.objects().allocate(provider,env,java.util.Collections.emptyMap());nodes.add(object.binding().node());
                var callable=session.arrays().allocate(false);callable.append(object.binding());callable.append(session.value(new php.runtime.memory.StringMemory("handle")));
                try (var call=session.newCallRoots()) {
                    call.send(callable.binding());Memory prior=call.invokeFunction(env.fetchFunction("set_error_handler"),TraceInfo.UNKNOWN);
                    if (i==0) assertSame(Memory.NULL,prior);else {assertSame(records.get(0).invokerMemory,prior);assertSame(prior,call.result().rawValue());}
                }
                records.add(env.getErrorHandler());object.close();callable.close();
            }
            assertSame(records.get(0),env.getPreviousErrorHandler());
            try (var call=session.newCallRoots()) {call.invokeFunction(env.fetchFunction("restore_error_handler"),TraceInfo.UNKNOWN);}
            assertSame(records.get(0),env.getErrorHandler());assertEquals(PhpOwnership.State.DISPOSED,nodes.get(1).state());assertNotEquals(PhpOwnership.State.DISPOSED,nodes.get(0).state());
            assertEquals("destroy;",env.getDefaultBuffer().getContents().toString());
            try (var call=session.newCallRoots()) {call.invokeFunction(env.fetchFunction("restore_error_handler"),TraceInfo.UNKNOWN);}
            assertNull(env.getErrorHandler());assertEquals(PhpOwnership.State.DISPOSED,nodes.get(0).state());
            env.getDefaultBuffer().flush();assertEquals("destroy;destroy;",output.toString(StandardCharsets.UTF_8));
        } finally {try {env.doFinal();} finally {participant.close();}}
    }

    public static Memory serialize(Environment env,TraceInfo trace,Memory value) {throw new AssertionError("Unadmitted native body executed");}
    @Test public void exactNativeMembersRejectSameNameAndHistoricalSerdeBeforeBody() throws Throwable {
        var exact=PhpSerialization.class.getMethod("serialize",Environment.class,TraceInfo.class,Memory.class);
        assertEquals(exact,PhpOwnershipNativeCalls.requireCapability(exact).member());
        var fake=getClass().getMethod("serialize",Environment.class,TraceInfo.class,Memory.class);
        assertThrows(IllegalArgumentException.class,()->PhpOwnershipNativeCalls.requireCapability(fake));
        var historical=php.runtime.ext.core.StringFunctions.class.getMethod("serialize",Environment.class,TraceInfo.class,Memory.class);
        assertThrows(IllegalArgumentException.class,()->PhpOwnershipNativeCalls.requireCapability(historical));
    }
    @Test public void ordinaryEnvironmentDoesNotManufactureRegistrationOwnership() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {assertNull(PhpOwnershipNativeCalls.retainHandler(env,null,Memory.NULL));assertNull(PhpOwnershipExecution.current(env));}
        finally {env.doFinal();}
    }
}
