package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.ext.core.LangFunctions;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real native registration and Env placements; no host wrapper is a PHP owner. */
public class PhpRuntimeErrorHandlerFieldTest {
    private static final ThreadLocal<php.runtime.exceptions.CriticalException> FAILURE=new ThreadLocal<>();
    public static final class ProbeExtension extends php.runtime.ext.support.Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new ProbeFunctions());}
    }
    public static final class ProbeFunctions extends php.runtime.ext.support.compile.FunctionsContainer {
        public static Memory handler_actual_arguments(Environment env) {
            assertNotNull(env.peekCall(0));assertEquals("All four surplus arguments remain real callback arguments",4,env.peekCall(0).args.length);
            assertTrue(env.peekCall(0).args[0] instanceof LongMemory);assertTrue(env.peekCall(0).args[1] instanceof StringMemory);
            if(FAILURE.get()!=null)throw FAILURE.get();return Memory.TRUE;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Environment env;
        final php.runtime.reflection.ModuleEntity module;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority;
        final PhpOwnershipDomain.Execution execution;
        Fixture(boolean debug,boolean dump,String source)throws Throwable {
            this(debug,dump,source,true);
        }
        Fixture(boolean debug,boolean dump,String source,boolean register)throws Throwable {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.registerExtension(new ProbeExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var entity=new JvmCompiler(env,context).compile(false);
            if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(entity,bytes);entity=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=entity;scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            authority=new PhpRuntimeOwnership.Authority(env,domain);execution=authority.enter();
            var members=new ArrayList<java.lang.reflect.Method>();for(var member:LangFunctions.class.getDeclaredMethods())if(Set.of("set_error_handler","restore_error_handler").contains(member.getName()))members.add(member);
            members.add(ProbeFunctions.class.getMethod("handler_actual_arguments",Environment.class));
            for(var member:members)
                authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                    public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                    public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){
                        if(member.getName().equals("set_error_handler"))assertTrue(arguments.getFirst().raw() instanceof ObjectMemory);
                    }
                });
            if(register)register();
        }
        void register()throws Throwable {
            for(var closure:module.getClosures()) {
                var method=closure.findMethod("__invoke");authority.registerMethod(method,closure.getData(),(plan,member,bytes)->{});
            }
            authority.registerModule(module,module.getData(),(plan,member,bytes)->{});
        }
        void include()throws Throwable {try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}}
        String text()throws Exception{return env.getDefaultBuffer().getContents().toString();}
        @Override public void close()throws Exception {
            try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}
            finally{try{authority.close();}finally{execution.close();}}
        }
    }

    @Test public void fieldSurvivesModuleReturnDispatchesFourArgumentsAndRestoreReleases()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,
                "set_error_handler(function($n,$s,$file,$line){echo $s;return true;});return 7;")) {
            f.include();var registration=f.env.getErrorHandler().getOwnershipRegistration();assertTrue(registration.isOrdinary());
            var node=registration.binding().node();assertEquals(PhpOwnership.State.LIVE,node.state());assertEquals(1,node.incoming());
            f.env.warning(TraceInfo.UNKNOWN,"actual warning");assertEquals("actual warning",f.text());
            assertEquals("FIELD restored after suspension",1,node.incoming());
            assertTrue(LangFunctions.restore_error_handler(f.env));assertNull(f.env.getErrorHandler());assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }

    @Test public void capturedCellArrayEntryAndSurplusCallbackArgumentsUseActualRoots()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,
                "$ev=[];set_error_handler(function($n,$s)use(&$ev){$ev[]=[$n,$s];return true;});return 7;")) {
            f.include();var cell=f.authority.globalAddress("ev").getRuntimeHolder().reference();var origin=cell.getRuntimeCell();assertEquals(2,origin.node().incoming());
            f.env.warning(TraceInfo.UNKNOWN,"entry");var array=(ArrayMemory)cell.getRawValue();assertEquals(1,array.size());
            var row=array.getByScalar(LongMemory.valueOf(0)).toValue(ArrayMemory.class);assertEquals(2,row.size());assertEquals("entry",row.getByScalar(LongMemory.valueOf(1)).toString());
            assertEquals("Capture and global aliases share one payload",2,origin.node().incoming());
            assertTrue(LangFunctions.restore_error_handler(f.env));assertEquals(1,origin.node().incoming());
        }
    }

    @Test public void previousStackAndFinalizationReleaseBothRealFields()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,
                "set_error_handler(function(){echo 'A';return true;});set_error_handler(function(){echo 'B';return true;});return 7;")) {
            f.include();var top=f.env.getErrorHandler().getOwnershipRegistration().binding().node();
            var old=f.env.getPreviousErrorHandler().getOwnershipRegistration().binding().node();assertEquals(1,top.incoming());assertEquals(1,old.incoming());
            f.env.warning(TraceInfo.UNKNOWN,"ignored");assertEquals("B",f.text());assertTrue(LangFunctions.restore_error_handler(f.env));assertEquals(PhpOwnership.State.DISPOSED,top.state());
            f.env.warning(TraceInfo.UNKNOWN,"ignored");assertEquals("BA",f.text());f.env.releaseOwnedErrorHandlers();assertNull(f.env.getErrorHandler());assertEquals(PhpOwnership.State.DISPOSED,old.state());
        }
    }

    @Test public void suspendedFieldReplacementRetiresOldOnlyAfterCheckedCallbackResult()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,
                "set_error_handler(function(){set_error_handler(function(){echo 'B';return true;});return handler_actual_arguments();});return 7;")) {
            f.include();var old=f.env.getErrorHandler().getOwnershipRegistration().binding().node();
            f.env.warning(TraceInfo.UNKNOWN,"first");assertEquals(PhpOwnership.State.DISPOSED,old.state());
            var replacement=f.env.getErrorHandler().getOwnershipRegistration().binding().node();assertEquals(1,replacement.incoming());
            f.env.warning(TraceInfo.UNKNOWN,"second");assertEquals("B",f.text());assertEquals(1,replacement.incoming());
        }
    }

    @Test public void failingCallbackRestoresSameFieldAndPrimaryIdentityAndPopsActualCallStack()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,
                "set_error_handler(function(){return handler_actual_arguments();});return 7;")) {
            f.include();var handler=f.env.getErrorHandler();var node=handler.getOwnershipRegistration().binding().node();
            var primary=new php.runtime.exceptions.CriticalException("callback primary");int depth=f.env.getCallStack().getTop();
            FAILURE.set(primary);
            try {var failure=assertThrows(RuntimeException.class,()->f.env.warning(TraceInfo.UNKNOWN,"first"));assertSame(primary,failure.getCause());}
            finally {FAILURE.remove();}
            assertSame(handler,f.env.getErrorHandler());assertEquals(1,node.incoming());assertNull(f.authority.currentFrame());
            assertEquals(depth,f.env.getCallStack().getTop());f.env.warning(TraceInfo.UNKNOWN,"retry");assertEquals(1,node.incoming());
        }
    }

    @Test public void typedDefaultAndOpaquePriorHandlerRejectBeforeCallerEffects()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})for(String parameter:new String[]{"int $n","$n=0","&$n","...$n","$a,$b,$c,$d,$e"}) {
            try(var f=new Fixture(debug,dump,"echo 'effect';set_error_handler(function("+parameter+"){return true;});return 7;",false)) {
                try{f.register();f.include();fail("Incomplete callback argument protocol admitted");}
                catch(IllegalArgumentException | IOException expected){}
                assertEquals("",f.text());assertNull(f.env.getErrorHandler());assertEquals(0,f.domain.graph().liveNodes());
            }
        }
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,
                "echo 'effect';set_error_handler(function(){return true;});return 7;")) {
            Memory callable=new StringMemory("handler_actual_arguments");var invoker=php.runtime.invoke.Invoker.valueOf(f.env,TraceInfo.UNKNOWN,callable);
            assertNotNull(invoker);var opaque=new php.runtime.env.handler.ErrorHandler(invoker,callable,-1);f.env.setErrorHandler(opaque);
            assertThrows(IllegalArgumentException.class,f::include);assertSame(opaque,f.env.getErrorHandler());assertEquals("",f.text());assertEquals(0,f.domain.graph().liveNodes());
        }
    }
}
