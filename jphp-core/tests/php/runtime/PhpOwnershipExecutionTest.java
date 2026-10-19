package php.runtime;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Actual source/PHB bodies import SEND roots and transfer checked returns before callee cleanup. */
public class PhpOwnershipExecutionTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final Environment env;final ModuleEntity module;final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;final PhpOwnershipExecution.Session session;final PhpOwnershipExecution.Scope scope;
        Fixture(boolean debug,boolean dumped,String body)throws Throwable {
            var compileScope=newScope();compileScope.setDebugMode(debug);env=new Environment(compileScope,new ByteArrayOutputStream());
            var context=new Context(body);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var compiled=new JvmCompiler(env,context).compile(false);
            if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(compiled,bytes);compiled=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=compiled;env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);for(var clazz:module.getClasses())clazz.initializeRuntimeState(env);
            participant=domain.participant(env);session=new PhpOwnershipExecution.Session(participant,env);scope=session.enter();
        }
        FunctionEntity admit(String name)throws Throwable {var function=module.findFunction(name);assertNotNull(function);var member=function.getNativeMethod();var plan=OwnershipFrameCompiler.read(function.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));assertNotNull(plan);assertEquals(plan.unsupported().toString(),Collections.emptyList(),plan.unsupported());session.registerGenerated(function,member,function.getData(),plan.runtimePlan(),(p,m,b)->{});return function;}
        PhpOwnershipObjects.Allocation object(String name)throws Throwable {var clazz=env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);var provider=session.objects().registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(c,e)->{});var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();for(var property:provider.properties())defaults.put(property,session.value(property.getDefaultValue(env).toImmutable()));return session.objects().allocate(provider,env,defaults);}
        public void close()throws Exception {scope.close();try(var execution=participant.enter()){participant.close();}try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }
    private void reject(Throwing work)throws Throwable {try{work.run();fail("expected pre-publication rejection");}catch(IllegalArgumentException|IllegalStateException expected){}}
    @FunctionalInterface private interface Throwing {void run()throws Throwable;}

    @Test public void actualArgumentAndCheckedObjectReturnSurviveCalleeCloseSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"class OEItem {} function oeKeep($a){$b=$a;$a=null;return $b;}")) {
            var function=f.admit("oeKeep");var allocation=f.object("OEItem");var binding=allocation.binding();var node=binding.node();var object=allocation.object();
            try(var roots=f.session.newCallRoots()){roots.send(0,binding);allocation.close();assertEquals(1,node.incoming());Memory returned=roots.invokeFunction(function,TraceInfo.UNKNOWN);assertSame(object,((ObjectMemory)returned).value);assertSame(node,roots.result().node());assertEquals(1,node.incoming());assertEquals(PhpOwnership.State.LIVE,node.state());assertNull(PhpOwnershipExecution.currentEntry(f.env));}
            assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void pendingReturnOwnsActualObjectAcrossFinallyReplacementSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"class OEFinally {} function oeFinally($a){try{return $a;}finally{$a=null;}}")) {
            var function=f.admit("oeFinally");var allocation=f.object("OEFinally");var node=allocation.binding().node();var object=allocation.object();
            try(var roots=f.session.newCallRoots()){roots.send(0,allocation.binding());allocation.close();Memory raw=roots.invokeFunction(function,TraceInfo.UNKNOWN);assertSame(object,((ObjectMemory)raw).value);assertEquals(1,node.incoming());assertSame(node,roots.result().node());}
            assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void failedActualReturnTypeCheckUnwindsImportsAndPreservesFailureSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"class OEBad {} function oeBad($a):int{return $a;}")) {
            var function=f.admit("oeBad");var allocation=f.object("OEBad");var node=allocation.binding().node();
            try(var roots=f.session.newCallRoots()){roots.send(0,allocation.binding());allocation.close();try{roots.invokeFunction(function,TraceInfo.UNKNOWN);fail("return type ignored");}catch(php.runtime.lang.exception.BaseTypeError expected){assertTrue(expected.getMessage(f.env).toString().contains("oeBad"));assertSame(node,expected.getTrace(f.env).valueOfIndex(0).valueOfIndex("args").valueOfIndex(0).toValue(ObjectMemory.class).value instanceof php.runtime.lang.support.IPhpOwnershipObject ? ((php.runtime.lang.support.IPhpOwnershipObject)expected.getTrace(f.env).valueOfIndex(0).valueOfIndex("args").valueOfIndex(0).toValue(ObjectMemory.class).value).getOwnershipObject().node() : null);assertSame(expected,((ObjectMemory)roots.failureBinding(expected).rawValue()).value);}assertEquals(PhpOwnership.State.LIVE,node.state());assertNull(roots.result());assertNull(PhpOwnershipExecution.currentEntry(f.env));}
            assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void scalarDefaultAndOrdinaryLaneKeepExistingSemanticsSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"function oeDefault($a=7):int{return $a;}")) {
            var function=f.admit("oeDefault");try(var roots=f.session.newCallRoots()){assertEquals(7,roots.invokeFunction(function,TraceInfo.UNKNOWN).toLong());assertNull(roots.result().node());}
            f.scope.close();assertNull(PhpOwnershipExecution.current(f.env));assertEquals(7,php.runtime.invoke.InvokeHelper.call(f.env,TraceInfo.UNKNOWN,function,new Memory[0]).toLong());
        }
    }
    @Test public void unplannedBodyAndForeignArgumentRejectBeforeInvocationSourceAndDump()throws Throwable {
        new PhpOwnershipExecution.FramePlan(1,"owner","body","()V",PhpOwnershipExecution.EntryKind.FUNCTION,List.of(),Set.of(),List.of(),10,11,new int[0]);
        reject(()->new PhpOwnershipExecution.FramePlan(2,"owner","body","()V",PhpOwnershipExecution.EntryKind.FUNCTION,List.of(),Set.of(),List.of(),10,11,new int[0],new int[]{12},new int[]{13}));
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"function oeOpaque($a){return [$a];} function oeScalar($a){return $a;}")) {
            var opaque=f.module.findFunction("oeOpaque");var member=opaque.getNativeMethod();var plan=OwnershipFrameCompiler.read(opaque.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));assertFalse(plan.unsupported().isEmpty());
            reject(()->f.session.registerGenerated(opaque,member,opaque.getData(),plan.runtimePlan(),(p,m,b)->{}));
            var function=f.admit("oeScalar");var other=new PhpOwnershipDomain();var participant=other.participant(f.env);try(var execution=participant.enter()){var foreign=PhpOwnershipValues.bindValue(other,Memory.CONST_INT_1,null);try(var roots=f.session.newCallRoots()){reject(()->roots.send(0,foreign));assertEquals(0,roots.size());}}
            try(var roots=f.session.newCallRoots()){roots.send(0,f.session.value(Memory.CONST_INT_1));assertEquals(1,roots.invokeFunction(function,TraceInfo.UNKNOWN).toLong());}
        }
    }
}
