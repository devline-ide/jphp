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
public class PhpSessionCellClosureTest {
    private static final String SOURCE="class CaptureTarget {public int $x=7;} class CaptureToken {} "
        +"function makeValue($v){return static function($o)use($v){};} "
        +"function makeMissing($v){return static function($o)use($v,$absent){};} "
        +"function makeCell(&$v){return static function($o)use(&$v){};} "
        +"function makeBound($v){return function($o)use($v){};} "
         +"function makeStaticLocal($v){return static function($o)use($v){static $n=1;};} "
        +"function makeMissingCell(){return static function($o)use(&$missing){return $missing;};} "
        +"function makeCellUnset($v){$callback=static function($o)use(&$v){};unset($v);return $callback;} "
        +"function makeSharedCell($v){$first=static function($o)use(&$v){$v=9;return $v;};return static function($o)use(&$v,$first){return $v;};} "
        +"function makeCellSnapshot($v){$anchor=static function($o)use(&$v){};$copy=static function($o)use($v){return $v;};$v=9;return $copy;} "
        +"function makeCellWarning($v){return static function($o)use(&$v,$absent){};}";
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
        void admitLexicals(FunctionEntity function)throws Throwable {
            for(String capability:plan(function.getNativeMethod(),function.getData()).required()) {
                if(!capability.startsWith("closure-value-producer-v1:") && !capability.startsWith("closure-capture-producer-v2:"))continue;
                var entity=module.findClosure(Integer.parseInt(capability.substring(capability.lastIndexOf(':')+1)));
                assertNotNull(entity);var invoke=entity.findMethod("__invoke");admit(invoke,invoke.getNativeMethod(),entity.getData());
            }
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
            if(failure!=null)PhpSessionCellClosureTest.<Exception>raise(failure);
        }
    }
    private static Memory call(Fixture f,PhpOwnershipValues.Binding callback)throws Throwable {
        var closure=(Closure)((ObjectMemory)callback.rawValue()).value;
        try(var roots=f.session.newCallRoots()) {
            roots.stageReceiver(callback);roots.send(f.session.value(Memory.NULL));
            return f.session.invokeCheckedMethod(closure.getReflection().findMethod("__invoke"),closure,f.trace,roots,true);
        }
    }
    @Test public void missingCellHasNoWarningAndCertifiedImportsRetainCanonicalIdentity()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb)) {
            var maker=f.maker("makeMissingCell");f.admitLexicals(maker);int[] warnings={0};
            f.env.setErrorReportHandler(new php.runtime.env.handler.ErrorReportHandler(){
                @Override public boolean onFatal(php.runtime.exceptions.support.ErrorException error){throw new AssertionError(error);}
                @Override public boolean onError(php.runtime.env.message.SystemMessage message){warnings[0]++;throw new AssertionError(message.getMessage());}
            });
            try(var creation=f.session.newCallRoots()) {
                creation.invokeFunction(maker,f.trace);var callback=creation.result();var closure=(Closure)((ObjectMemory)callback.rawValue()).value;
                var capture=closure.ownershipClosure().captureBindings().get(0);assertEquals(PhpOwnershipValues.Role.CELL,capture.role());
                var cell=(ReferenceMemory)capture.rawValue();assertSame(Memory.NULL,cell.getRawValue());var captureNode=capture.node();
                try(var keep=callback.host()) {creation.close();assertTrue(call(f,callback).isNull());assertTrue(call(f,callback).isNull());
                    assertSame(cell,closure.ownershipClosure().captureBindings().get(0).rawValue());assertEquals(0,warnings[0]);
                }
                assertEquals(PhpOwnership.State.DISPOSED,captureNode.state());
            }
            assertNull(PhpOwnershipExecution.currentEntry(f.env));
        }
    }
    @Test public void cellOutlivesUnsetCreatorCvAndLazyFieldReleasesItsActualPayload()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb);var token=f.token();var reflection=f.reflector()) {
            var maker=f.maker("makeCellUnset");f.admitLexicals(maker);var tokenNode=token.binding().node();
            try(var creation=f.session.newCallRoots()) {
                creation.send(token.binding());creation.invokeFunction(maker,f.trace);var callback=creation.result();
                var closure=(Closure)((ObjectMemory)callback.rawValue()).value;var capture=closure.ownershipClosure().captureBindings().get(0);
                assertEquals(PhpOwnershipValues.Role.CELL,capture.role());var cell=(ReferenceMemory)capture.rawValue();var captureNode=capture.node();
                assertSame(token.object(),((ObjectMemory)cell.getRawValue()).value);
                try(var keep=callback.host()) {creation.close();token.close();assertEquals(1,tokenNode.incoming());
                    try(var ghost=f.session.newCallRoots()) {
                        ghost.send(callback);var shell=((ObjectMemory)f.invoke(reflection,"newLazyGhost",ghost)).value;var shellBinding=ghost.result();
                        try(var shellRoot=shellBinding.host()) {ghost.close();keep.close();
                            assertEquals(1,capture.node().incoming());assertEquals(1,tokenNode.incoming());
                            try(var initialize=f.session.newCallRoots()){initialize.send(shellBinding);f.invoke(reflection,"initializeLazyObject",initialize);}
                            assertEquals(PhpOwnership.State.DISPOSED,captureNode.state());assertEquals(PhpOwnership.State.DISPOSED,tokenNode.state());
                        }
                    }
                }
            }
            assertNull(PhpOwnershipExecution.currentEntry(f.env));
        }
    }
    @Test public void twoClosuresShareOneCellAndValueCaptureSnapshotsBeforeLaterMutation()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb)) {
            var maker=f.maker("makeSharedCell");f.admitLexicals(maker);
            try(var creation=f.session.newCallRoots()) {
                creation.send(f.session.value(LongMemory.valueOf(7)));creation.invokeFunction(maker,f.trace);var second=creation.result();
                var captures=((Closure)((ObjectMemory)second.rawValue()).value).ownershipClosure().captureBindings();var shared=captures.get(0);var first=captures.get(1);var sharedNode=shared.node();var firstNode=first.node();
                var firstCaptures=((Closure)((ObjectMemory)first.rawValue()).value).ownershipClosure().captureBindings();
                assertEquals(PhpOwnershipValues.Role.CELL,shared.role());assertSame(shared.node(),firstCaptures.get(0).node());
                assertSame(shared.rawValue(),firstCaptures.get(0).rawValue());
                try(var keep=second.host()) {creation.close();assertEquals(7,call(f,second).toLong());assertEquals(9,call(f,first).toLong());assertEquals(9,call(f,second).toLong());}
                assertEquals(PhpOwnership.State.DISPOSED,sharedNode.state());assertEquals(PhpOwnership.State.DISPOSED,firstNode.state());
            }
            var snapshot=f.maker("makeCellSnapshot");f.admitLexicals(snapshot);
            try(var creation=f.session.newCallRoots()) {
                creation.send(f.session.value(LongMemory.valueOf(7)));creation.invokeFunction(snapshot,f.trace);var callback=creation.result();
                var capture=((Closure)((ObjectMemory)callback.rawValue()).value).ownershipClosure().captureBindings().get(0);
                assertEquals(PhpOwnershipValues.Role.VALUE,capture.role());assertEquals(7,capture.rawValue().toLong());
                try(var keep=callback.host()){creation.close();assertEquals(7,call(f,callback).toLong());}
            }
        }
    }
    @Test public void warningAfterCellCapturePreservesFailureAndRejectsCallbackForgeryWithoutOwnerDrift()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb);var token=f.token()) {
            var maker=f.maker("makeCellWarning");f.admitLexicals(maker);var tokenNode=token.binding().node();var marker=new IllegalStateException("cell-warning");
            var declaration=f.plan(maker.getNativeMethod(),maker.getData());int local=declaration.runtimePlan().locals().stream().filter(v->v.name().equals("v")).findFirst().orElseThrow().id();
            String[] site=declaration.required().stream().filter(v->v.startsWith("closure-capture-producer-v2:")).findFirst().orElseThrow().split(":");
            PhpOwnership.Node[] capturedCell={null};int[] warnings={0};
            f.env.setErrorReportHandler(new php.runtime.env.handler.ErrorReportHandler(){
                @Override public boolean onFatal(php.runtime.exceptions.support.ErrorException error){throw new AssertionError(error);}
                @Override public boolean onError(php.runtime.env.message.SystemMessage message){
                    assertTrue(message.getMessage().contains("Undefined variable $absent"));warnings[0]++;
                    var entry=PhpOwnershipExecution.currentEntry(f.env);var address=(ReferenceMemory)entry.localAddress(local,"v");var cell=(ReferenceMemory)address.getRawValue();
                    capturedCell[0]=cell.getOwnershipCell().node();assertTrue(capturedCell[0].incoming()>=2);assertEquals(2,tokenNode.incoming());
                    long owners=capturedCell[0].incoming();
                    try{entry.prepareClosure(Integer.parseInt(site[1]),Integer.parseInt(site[2]),null);fail("Callback replayed a genuine literal instruction");}
                    catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("exact generated member"));}catch(Throwable error){throw new AssertionError(error);}
                    try{entry.unsetLocal(local,address);fail("Callback forged a CV unlink");}
                    catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("exact generated member"));}catch(Throwable error){throw new AssertionError(error);}
                    try{entry.importCapture(0,local,cell,PhpOwnershipExecution.VALUE);fail("Argument CV accepted forged capture role");}
                    catch(IllegalArgumentException expected){}catch(Throwable error){throw new AssertionError(error);}
                    assertEquals(owners,capturedCell[0].incoming());throw marker;
                }
            });
            try(var roots=f.session.newCallRoots()) {roots.send(token.binding());try{roots.invokeFunction(maker,f.trace);fail("Warning throw was swallowed");}catch(Throwable failure){assertSame(marker,failure);}}
            assertEquals(1,warnings[0]);assertNotNull(capturedCell[0]);assertEquals(PhpOwnership.State.DISPOSED,capturedCell[0].state());
            assertEquals(1,tokenNode.incoming());token.close();assertEquals(PhpOwnership.State.DISPOSED,tokenNode.state());assertNull(PhpOwnershipExecution.currentEntry(f.env));
            try(var foreign=new Fixture(debug,phb);var object=foreign.token()) {
                try(var roots=f.session.newCallRoots()){try{roots.send(object.binding());fail("Foreign CELL payload entered invocation packet");}catch(IllegalArgumentException expected){}}
                try(var foreignCell=foreign.session.arrays().allocateCell(foreign.session.value(LongMemory.valueOf(7)));var roots=f.session.newCallRoots()) {
                    try{roots.send(foreignCell.binding());fail("Foreign canonical CELL entered invocation packet");}catch(IllegalArgumentException expected){}
                }
            }
            var byrefArgument=f.module.findFunction("makeCell");var unsupported=f.plan(byrefArgument.getNativeMethod(),byrefArgument.getData());assertFalse(unsupported.unsupported().isEmpty());
            try{f.session.registerGenerated(byrefArgument,byrefArgument.getNativeMethod(),byrefArgument.getData(),unsupported.runtimePlan(),(p,m,b)->fail("Unclosed byref-ARGUMENT protocol reached issuer"));fail();}
            catch(IllegalArgumentException expected){}
        }
    }
    @Test public void ordinaryMixedCellCaptureMatchesPinnedNativeWithoutSession()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var root=java.nio.file.Path.of(System.getProperty("qa.cell.fixture"));
            var context=new Context(root.resolve("mixed.php").toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
            if(phb){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            try{scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);}finally{env.doFinal();}
            assertArrayEquals(java.nio.file.Files.readAllBytes(root.resolve("mixed.stdout")),output.toByteArray());System.out.println("SESSION_CELL_ORDINARY_NATIVE debug="+debug+" PHB="+phb);
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void raise(Throwable error)throws E{throw(E)error;}
}
