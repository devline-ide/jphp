package php.runtime;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.*;
import static org.junit.Assert.*;

/** Real generated literal -> EVAL -> RETURN -> lazy initializer FIELD, not a host-created callback. */
public class PhpSessionValueClosureTest {
    private static final String SOURCE="class CaptureTarget {public int $x=7;} class CaptureToken {} "
        +"function makeValue($v){return static function($o)use($v){};} "
        +"function makeMissing($v){return static function($o)use($v,$absent){};} "
        +"function makeCell(&$v){return static function($o)use(&$v){};} "
        +"function makeBound($v){return function($o)use($v){};} "
        +"function makeStaticLocal($v){return static function($o)use($v){static $n=1;};}";
    private static final class Fixture implements AutoCloseable {
        final Environment env;final ModuleEntity module;final TraceInfo trace;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;final PhpOwnershipExecution.Session session;
        final PhpOwnershipExecution.Scope active;
        final Set<Object> admitted=Collections.newSetFromMap(new IdentityHashMap<>());
        Fixture(boolean debug,boolean phb)throws Throwable {
            var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
            env=new Environment(scope,new ByteArrayOutputStream());
            var context=new Context(SOURCE);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);trace=new TraceInfo(context);
            var compiled=new JvmCompiler(env,context).compile(false);
            if(phb){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(compiled,bytes);compiled=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=compiled;scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            for(var entity:module.getClasses())entity.initializeRuntimeState(env);
            participant=domain.participant(env);session=new PhpOwnershipExecution.Session(participant,env);active=session.enter();
        }
        ClassEntity clazz(String name){return env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);}
        OwnershipFrameCompiler.Plan plan(Method member,byte[] bytes)throws Throwable {
            var plan=OwnershipFrameCompiler.read(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));assertNotNull(plan);return plan;
        }
        void admit(Object entity,Method member,byte[] bytes)throws Throwable {
            if(!admitted.add(entity))return;
            var declaration=plan(member,bytes);assertEquals(declaration.unsupported().toString(),List.of(),declaration.unsupported());
            var actual=declaration.runtimePlan();session.registerGenerated(entity,member,bytes,actual,(p,m,b)->{
                assertSame(actual,p);assertEquals(member,m);assertArrayEquals(bytes,b);
            });
        }
        FunctionEntity maker(String name)throws Throwable {
            var function=module.findFunction(name);assertNotNull(function);admit(function,function.getNativeMethod(),function.getData());return function;
        }
        ClosureEntity lexical(FunctionEntity function)throws Throwable {
            String capability=plan(function.getNativeMethod(),function.getData()).required().stream()
                .filter(c->c.startsWith("closure-value-producer-v1:")).findFirst().orElseThrow();
            var entity=module.findClosure(Integer.parseInt(capability.substring(capability.lastIndexOf(':')+1)));
            assertNotNull(entity);var invoke=entity.findMethod("__invoke");admit(invoke,invoke.getNativeMethod(),entity.getData());return entity;
        }
        PhpOwnershipObjects.Allocation token()throws Throwable {
            var entity=clazz("CaptureToken");PhpOwnershipObjects.Provider provider;
            try{provider=session.objects().provider(entity);}catch(IllegalArgumentException absent){provider=PhpObjectShellVerifier.register(session.objects(),env,entity);}
            return session.objects().allocate(provider,env,Map.of());
        }
        PhpOwnershipObjects.Allocation reflector()throws Throwable {
            var entity=clazz("ReflectionClass");var provider=PhpOwnershipReflectionCalls.registerShell(session.objects(),env,entity);
            var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for(var property:provider.properties())defaults.put(property,session.value(Memory.UNDEFINED));
            var allocation=session.objects().allocate(provider,env,defaults);
            try(var roots=session.newCallRoots()) {roots.send(session.value(new StringMemory("CaptureTarget")));invoke(allocation,"__construct",roots);}
            return allocation;
        }
        Memory invoke(PhpOwnershipObjects.Allocation receiver,String name,PhpOwnershipExecution.CallRoots roots)throws Throwable {
            roots.stageReceiver(receiver.binding());return session.invokeCheckedMethod(receiver.object().getReflection().findMethod(name.toLowerCase()),receiver.object(),trace,roots,true);
        }
        @Override public void close()throws Exception {
            Throwable failure=null;try{env.doFinal();}catch(Throwable error){failure=error;}
            try{active.close();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            try(var execution=participant.enter()){participant.close();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            if(failure!=null)PhpSessionValueClosureTest.<Exception>raise(failure);
        }
    }
    @Test public void staticValueCaptureTravelsFromRealEvalToLazyFieldAndRetiresAfterInitialization()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb);
                var token=f.token();var reflection=f.reflector()) {
            var maker=f.maker("makeValue");var entity=f.lexical(maker);var tokenNode=token.binding().node();
            try(var creation=f.session.newCallRoots()) {
                creation.send(token.binding());var returned=creation.invokeFunction(maker,f.trace);
                assertTrue(returned instanceof ObjectMemory);var closure=(Closure)((ObjectMemory)returned).value;
                assertSame(entity,closure.getReflection());assertNotNull(closure.ownershipClosure());
                var callback=creation.result();assertSame(tokenNode,closure.ownershipClosure().captureBindings().get(0).node());
                try(var keepCallback=callback.host()) {creation.close();token.close();assertEquals(1,tokenNode.incoming());
                    try(var ghost=f.session.newCallRoots()) {
                        ghost.send(callback);var raw=f.invoke(reflection,"newLazyGhost",ghost);var shell=((ObjectMemory)raw).value;
                        var shellBinding=ghost.result();var callbackNode=callback.node();
                        try(var keepGhost=shellBinding.host()) {ghost.close();keepCallback.close();
                            assertTrue(PhpLazyObjects.uninitialized(shell));assertEquals(1,callbackNode.incoming());assertEquals(1,tokenNode.incoming());
                            try(var initialize=f.session.newCallRoots()){initialize.send(shellBinding);assertSame(shell,((ObjectMemory)f.invoke(reflection,"initializeLazyObject",initialize)).value);}
                            assertFalse(PhpLazyObjects.uninitialized(shell));assertEquals(PhpOwnership.State.DISPOSED,callbackNode.state());
                            assertEquals(PhpOwnership.State.DISPOSED,tokenNode.state());
                        }
                    }
                }
            }
            assertNull(PhpOwnershipExecution.currentEntry(f.env));
        }
    }
    @Test public void missingCaptureWarningRunsAfterSnapshotOwnershipAndThrowUnwindsAllLiteralRoots()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb);var token=f.token()) {
            var maker=f.maker("makeMissing");f.lexical(maker);var node=token.binding().node();
            var marker=new IllegalStateException("capture-warning");int[] called={0};
            f.env.setErrorReportHandler(new php.runtime.env.handler.ErrorReportHandler(){
                @Override public boolean onFatal(php.runtime.exceptions.support.ErrorException error){throw new AssertionError(error);}
                @Override public boolean onError(php.runtime.env.message.SystemMessage message){
                assertTrue(message.getMessage().contains("Undefined variable $absent"));called[0]++;
                assertNotNull(PhpOwnershipExecution.currentEntry(f.env));
                // SEND transferred into CV: allocation+CV+already staged capture precede this report.
                assertTrue(node.incoming()>=3);
                try{PhpOwnershipExecution.currentEntry(f.env).prepareClosure(-1,0,null);fail("Unissued literal EVAL was accepted");}
                catch(IllegalArgumentException expected){}catch(Throwable failure){throw new AssertionError(failure);}
                // Even a genuine planned EVAL/declaration cannot be forged from this report callback.
                String issued;
                try {issued=f.plan(maker.getNativeMethod(),maker.getData()).required().stream()
                    .filter(c->c.startsWith("closure-value-producer-v1:")).findFirst().orElseThrow();}
                catch(Throwable failure){throw new AssertionError(failure);}
                String[] fields=issued.split(":");
                try{PhpOwnershipExecution.currentEntry(f.env).prepareClosure(Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),null);fail("Callback forged genuine planned literal site");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("exact generated member"));}
                catch(Throwable failure){throw new AssertionError(failure);}
                throw marker;
                }
            });
            try(var roots=f.session.newCallRoots()) {
                roots.send(token.binding());try{roots.invokeFunction(maker,f.trace);fail("Warning failure was swallowed");}
                catch(Throwable failure){System.out.println("CAPTURE_WARNING_ORIGINAL debug="+debug+" PHB="+phb);failure.printStackTrace(System.out);assertSame(marker,failure);}
                assertNull(PhpOwnershipExecution.currentEntry(f.env));assertEquals(1,called[0]);
            }
            assertEquals(1,node.incoming());token.close();assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void nonValueOrUnissuedClosureProtocolsAndUnmanagedCapturesStayRejected()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb)) {
            for(String name:List.of("makeCell","makeBound")) {
                var function=f.module.findFunction(name);var plan=f.plan(function.getNativeMethod(),function.getData());
                assertFalse(name,plan.unsupported().isEmpty());
                try{f.session.registerGenerated(function,function.getNativeMethod(),function.getData(),plan.runtimePlan(),(p,m,b)->fail("Incomplete protocol reached issuer"));fail(name);}
                catch(IllegalArgumentException expected){}
            }
            var staticMaker=f.module.findFunction("makeStaticLocal");
            assertFalse(f.plan(staticMaker.getNativeMethod(),staticMaker.getData()).unsupported().isEmpty());
            var unmanaged=new BaseObject(f.env,f.clazz("CaptureToken")){};
            try{f.session.value(new ObjectMemory(unmanaged));fail("Unissued capture OBJECT accepted");}catch(IllegalArgumentException expected){}
            var maker=f.maker("makeValue");f.lexical(maker);
            try(var other=new Fixture(debug,phb);var foreign=other.token()) {
                try(var roots=f.session.newCallRoots()){try{roots.send(foreign.binding());fail("Foreign capture SEND accepted");}catch(IllegalArgumentException expected){}}
            }
            assertNull(PhpOwnershipExecution.currentEntry(f.env));
        }
    }
    @Test public void ordinaryCaptureWarningsAndCowSnapshotsMatchPinnedNativeWithoutSession()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            var root=java.nio.file.Path.of(System.getProperty("qa.capture.fixture"));
            var context=new Context(root.resolve("ordinary.php").toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(phb){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            try{scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);}
            finally{env.doFinal();}
            assertArrayEquals(java.nio.file.Files.readAllBytes(root.resolve("ordinary.stdout")),output.toByteArray());
            System.out.println("SESSION_CAPTURE_ORDINARY_NATIVE debug="+debug+" PHB="+phb);
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void raise(Throwable error)throws E{throw(E)error;}
}
