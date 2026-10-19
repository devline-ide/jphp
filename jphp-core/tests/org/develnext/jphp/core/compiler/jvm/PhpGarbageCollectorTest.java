package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.InfoFunctions;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.Assert.*;

/** Public PHP GC API and the existing Java ownership collector; physical JVM GC is independent. */
public class PhpGarbageCollectorTest extends JvmCompilerCase {
    private ModuleEntity module(Environment env, boolean dump, String source) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var result=new JvmCompiler(env,context).compile(false);
        if(dump) {
            var output=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
            dumper.save(result,output);result=dumper.load(new ByteArrayInputStream(output.toByteArray()));
        }
        env.scope.loadModule(result);env.scope.addUserModule(result);env.registerModule(result);return result;
    }
    private void nativeGc(PhpRuntimeOwnership.Authority authority) throws Throwable {
        for(String name:List.of("gc_collect_cycles","gc_enable","gc_disable","gc_enabled")) {
            Method method=InfoFunctions.class.getMethod(name,Environment.class);
            authority.registerNative(method,new PhpRuntimeOwnership.CompleteNativeProtocol() {
                public void validateMember(Method actual) {assertEquals(method,actual);}
                public void validateArguments(List<PhpRuntimeOwnership.Value> actual) {assertTrue(actual.isEmpty());}
            });
        }
    }
    private PhpOwnership.Node cycle(PhpOwnershipDomain domain,List<Object> contexts) throws Throwable {
        var node=domain.object(new Object(),new PhpOwnershipDomain.Lifecycle() {
            public void destruct(PhpOwnership.Node actual,Object context) throws Throwable {
                contexts.add(context);assertSame(context,domain.currentContext());
                assertEquals(0,InfoFunctions.gc_collect_cycles((Environment)context));
            }
        });
        domain.graph().edge(node).replace(node);
        var root=domain.graph().root();root.replace(node);root.close();
        return node;
    }
    @Test public void phpFlagsAndExplicitCollectionWorkInSourceAndDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var result=module(env,dump,"$before=gc_enabled(); gc_disable(); $off=gc_enabled(); "
                        + "$collected=gc_collect_cycles(); gc_enable(); "
                        + "return [$before,$off,gc_enabled(),$collected];").include(env);
                assertTrue(result.valueOfIndex(0).toBoolean());assertFalse(result.valueOfIndex(1).toBoolean());
                assertTrue(result.valueOfIndex(2).toBoolean());assertEquals(0,result.valueOfIndex(3).toLong());
                assertNull(env.getRuntimeOwnership());assertNull(env.getOwnershipExecutionContext());
            } finally {env.doFinal();}
        }
    }
    @Test public void realPhpNativeCallCollectsSharedGraphEvenWhenAutomaticGcIsDisabled() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            var compiled=module(env,dump,"function gc_probe(){gc_disable();return gc_collect_cycles();} return 0;");
            compiled.include(env);var domain=new PhpOwnershipDomain();List<Object> contexts=new ArrayList<>();
            try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
                nativeGc(authority);var function=env.fetchFunction("gc_probe");
                authority.registerFunction(function,function.getData(),(plan,member,bytes)->assertTrue(plan.unsupportedLowering().isEmpty()));
                var node=cycle(domain,contexts);
                try(var result=authority.invoke(function,TraceInfo.UNKNOWN,new Memory[0])) {assertEquals(1,result.raw().toLong());}
                assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(List.of(env),contexts);
                assertFalse(InfoFunctions.gc_enabled(env));assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }
    @Test public void flagsArePerEnvironmentAndSharedCleanupUsesTheCallingParticipant() throws Throwable {
        var first=new Environment(newScope(),new ByteArrayOutputStream());
        var second=new Environment(newScope(),new ByteArrayOutputStream());
        var domain=new PhpOwnershipDomain();List<Object> contexts=new ArrayList<>();
        try(var a=new PhpRuntimeOwnership.Authority(first,domain);var b=new PhpRuntimeOwnership.Authority(second,domain)) {
            InfoFunctions.gc_disable(first);assertFalse(InfoFunctions.gc_enabled(first));assertTrue(InfoFunctions.gc_enabled(second));
            PhpOwnership.Node node;
            try(var execution=a.enter()) {node=cycle(domain,contexts);}
            assertEquals(1,InfoFunctions.gc_collect_cycles(second));
            assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(List.of(second),contexts);
            assertFalse(InfoFunctions.gc_enabled(first));assertTrue(InfoFunctions.gc_enabled(second));
        } finally {second.doFinal();first.doFinal();}
    }
    @Test public void ordinaryStdClassWorksWithoutSilentlyAdmittingIncompleteOwnership() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            var compiled=module(env,dump,"function gc_object_probe(){ $a=new stdClass(); $a->self=$a; "
                    + "unset($a); return gc_collect_cycles(); } return 0;");
            compiled.include(env);
            assertEquals(0,env.fetchFunction("gc_object_probe").invoke(env,TraceInfo.UNKNOWN,new Memory[0]).toLong());
            assertNull(env.getRuntimeOwnership());var domain=new PhpOwnershipDomain();
            try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
                nativeGc(authority);var function=env.fetchFunction("gc_object_probe");
                try {
                    authority.registerFunction(function,function.getData(),(plan,member,bytes)->assertTrue(plan.unsupportedLowering().isEmpty()));
                    fail("stdClass allocation/property writes still need their actual ownership protocol");
                } catch(IllegalArgumentException expected) {
                    assertTrue(expected.getMessage(),expected.getMessage().contains("PhpCallFrame.construct"));
                    assertTrue(expected.getMessage(),expected.getMessage().contains("ObjectInvokeHelper.assignProperty"));
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }
    @Test public void ordinaryArrayCycleWorksWithoutSilentlyAdmittingIncompleteOwnership() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            var compiled=module(env,dump,"function gc_array_probe(){ $a=[]; $a['self']=&$a; "
                    + "unset($a); return gc_collect_cycles(); } return 0;");
            compiled.include(env);
            assertEquals(0,env.fetchFunction("gc_array_probe").invoke(env,TraceInfo.UNKNOWN,new Memory[0]).toLong());
            assertNull(env.getRuntimeOwnership());var domain=new PhpOwnershipDomain();
            try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
                nativeGc(authority);var function=env.fetchFunction("gc_array_probe");
                try {
                    authority.registerFunction(function,function.getData(),(plan,member,bytes)->assertTrue(plan.unsupportedLowering().isEmpty()));
                    fail("Cyclic ARRAY reference assignment still needs its actual ownership protocol");
                } catch(IllegalArgumentException expected) {
                    assertTrue(expected.getMessage(),expected.getMessage().contains("NullCoalesceAssignment.assignRef"));
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }
    @Test public void phpCreatedLiteralObjectCycleReleasesItsReferencedPropertyBacking() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            var compiled=module(env,dump,"class GcLiteralNode {public $self;} "
                    + "function gc_literal_probe(){ $a=new GcLiteralNode; $ref=&$a->self; $ref=$a; "
                    + "unset($ref);unset($a);return gc_collect_cycles(); } return 0;");
            compiled.include(env);var domain=new PhpOwnershipDomain();
            try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
                nativeGc(authority);var function=env.fetchFunction("gc_literal_probe");
                authority.registerFunction(function,function.getData(),(plan,member,bytes)-> {
                    // Authority executes its guarded RuntimePlan; the older Session plan is separate.
                    var runtime=PhpRuntimeOwnership.readRuntimePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
                    assertTrue(runtime.unsupported().toString(),runtime.unsupported().isEmpty());
                    assertEquals(1,runtime.producers().stream().filter(producer->producer.kind().equals("OBJECT_NEW")).count());
                    assertEquals(PhpOwnershipExecution.EntryKind.FUNCTION,plan.entryKind());
                });
                try(var result=authority.invoke(function,TraceInfo.UNKNOWN,new Memory[0])) {assertTrue(result.raw().toLong()>0);}
                assertEquals(0,domain.graph().liveNodes());assertEquals(0,InfoFunctions.gc_collect_cycles(env));
            } finally {env.doFinal();}
        }
    }
    @Test public void collectorFailureIsPreservedAndOtherCyclesStillGetDisposed() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
        var failure=new Exception("GC destructor failure");List<Object> contexts=new ArrayList<>();
        try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
            var bad=domain.object(new Object(),new PhpOwnershipDomain.Lifecycle() {
                public void destruct(PhpOwnership.Node node,Object context) throws Throwable {throw failure;}
            });
            domain.graph().edge(bad).replace(bad);var root=domain.graph().root();root.replace(bad);root.close();
            var good=cycle(domain,contexts);
            try {InfoFunctions.gc_collect_cycles(env);fail("Expected original destructor failure");}
            catch(Exception actual) {assertSame(failure,actual);}
            assertEquals(PhpOwnership.State.DISPOSED,bad.state());assertEquals(PhpOwnership.State.DISPOSED,good.state());
            assertEquals(0,InfoFunctions.gc_collect_cycles(env));assertEquals(0,domain.graph().liveNodes());
        } finally {env.doFinal();}
    }
    @Test public void generatedLoopCollectsDuringAllocationWithoutLosingItsCurrentProducer() throws Throwable {
        for(boolean enabled:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(false);var env=new Environment(scope,new ByteArrayOutputStream());
            var compiled=module(env,true,"class GcLoopNode {public $self;} function gc_loop_probe(){ $i=0;$counter=&$i; "
                    + "while($i<10000){$a=new GcLoopNode;$ref=&$a->self;$ref=$a;unset($ref);unset($a);$i++;} "
                    + "return gc_enabled();} return 0;");
            compiled.include(env);var domain=new PhpOwnershipDomain();env.setGcEnabled(enabled);
            try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
                nativeGc(authority);var function=env.fetchFunction("gc_loop_probe");
                var smaller=PhpComparisonCalls.class.getMethod("smaller",Memory.class,Memory.class,Environment.class,TraceInfo.class);
                authority.registerNative(smaller,new PhpRuntimeOwnership.CompleteNativeProtocol() {
                    public void validateMember(Method actual) {assertEquals(smaller,actual);}
                    public void validateArguments(List<PhpRuntimeOwnership.Value> actual) {PhpComparisonCalls.validateOwnershipArguments(authority,actual);}
                });
                authority.registerFunction(function,function.getData(),(plan,member,bytes)-> {
                    var runtime=PhpRuntimeOwnership.readRuntimePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
                    assertTrue(runtime.unsupported().toString(),runtime.unsupported().isEmpty());
                });
                try(var result=authority.invoke(function,TraceInfo.UNKNOWN,new Memory[0])) {assertEquals(enabled,result.raw().toBoolean());}
                if(enabled) {
                    // The last cycle can remain below the batch threshold; it is collectible explicitly.
                    assertTrue(domain.graph().liveNodes()<10_000);assertFalse(domain.graph().shouldCollectCycles());
                    InfoFunctions.gc_collect_cycles(env);assertEquals(0,domain.graph().liveNodes());
                }
                else {
                    assertTrue(domain.graph().shouldCollectCycles());assertTrue(domain.graph().liveNodes()>0);
                    env.setGcEnabled(true);env.collectGarbageIfNeeded();assertEquals(0,domain.graph().liveNodes());
                }
            } finally {env.doFinal();}
        }
    }
    @Test public void automaticSafePointHonorsDisableAndCollectsDistinctCandidateBatch() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
        try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
            InfoFunctions.gc_disable(env);
            var frame=authority.frame();
            for(int i=0;i<10_000;i++) {
                var node=domain.storage(new Object());domain.graph().edge(node).replace(node);
                var root=domain.graph().root();root.replace(node);root.close();
            }
            assertTrue(domain.graph().shouldCollectCycles());frame.close();assertEquals(10_000,domain.graph().liveNodes());
            InfoFunctions.gc_enable(env);
            try(var safePoint=authority.frame()) {assertEquals(10_000,domain.graph().liveNodes());}
            assertEquals(0,domain.graph().liveNodes());assertFalse(domain.graph().shouldCollectCycles());
            assertEquals(0,InfoFunctions.gc_collect_cycles(env));
        } finally {env.doFinal();}
    }
    @Test public void repeatedReleasesOfOneLiveIdentityDoNotCauseWholeGraphScans() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
        try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter();var frame=authority.frame()) {
            var node=domain.storage(new Object());
            var keep=domain.graph().root();var temporary=domain.graph().root();
            try {
                keep.replace(node);
                for(int i=0;i<20_000;i++) {temporary.replace(node);temporary.replace(null);}
                assertFalse(domain.graph().shouldCollectCycles());env.collectGarbageIfNeeded();
                assertEquals(PhpOwnership.State.LIVE,node.state());
            } finally {temporary.close();keep.close();}
            assertEquals(0,domain.graph().liveNodes());assertFalse(domain.graph().shouldCollectCycles());
        } finally {env.doFinal();}
    }
    @Test public void gcFunctionExportsHaveOneZeroArgumentMember() {
        var container=new InfoFunctions();
        for(var function:container.getFunctions())if(function.name.startsWith("gc_")) {
            assertEquals(0,function.getMinArgs());assertEquals(0,function.getMaxArgs());
            assertArrayEquals(new Class<?>[]{Environment.class},function.find(0).method.getParameterTypes());
        }
        for(var spec:container.getFunctionSpecs())if(spec.getName().startsWith("gc_")) {
            assertArrayEquals(new Class<?>[]{Environment.class},spec.toFunction().find(0).method.getParameterTypes());
        }
    }
}
