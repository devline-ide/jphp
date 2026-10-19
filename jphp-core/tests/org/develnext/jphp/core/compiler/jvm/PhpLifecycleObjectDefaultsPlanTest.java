package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.PhpObjectShellVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Separate exact lifecycle/default capability; literal NEW keeps its no-destructor contract. */
public class PhpLifecycleObjectDefaultsPlanTest extends JvmCompilerCase {
    @FunctionalInterface private interface Work {void run() throws Throwable;}
    private void reject(Work work) throws Throwable {
        try {work.run();fail("Expected incomplete lifecycle/default proof rejection");}
        catch(IllegalArgumentException|IllegalStateException expected) {}
    }
    private final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Environment env;
        final ClassEntity entity;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;
        final PhpOwnershipDomain.Execution execution;
        final PhpOwnershipObjects objects;
        int calls;
        Fixture(boolean debug,boolean dumped,boolean strict,String source,String name) throws Throwable {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            var context=new Context((strict?"declare(strict_types=1);":"")+source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(dumped){var out=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,out);module=dumper.load(new ByteArrayInputStream(out.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            entity=module.getClasses().stream().filter(value->value.getName().equals(name)).findFirst().orElseThrow();
            participant=domain.participant(env);execution=participant.enter();objects=new PhpOwnershipObjects(domain.heap(),new PhpOwnershipArrays(domain.heap()));
        }
        PhpOwnershipObjects.CompleteDestructorProtocol protocol() {
            return new PhpOwnershipObjects.CompleteDestructorProtocol() {
                public void validateMember(ClassEntity actual,MethodEntity method,Environment environment) {
                    assertSame(entity,actual);assertSame(entity.methodDestruct,method);assertSame(env,environment);
                }
                public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment environment,TraceInfo trace) throws Throwable {
                    calls++;var method=entry.method();var object=entry.consume(domain,environment,method);
                    var certificate=((php.runtime.lang.BaseObject)object).getOwnershipObject();
                    assertEquals(PhpOwnership.State.FINALIZING,certificate.node().state());assertEquals(0,certificate.node().incoming());
                    method.invokeDynamic(object,environment,trace);entry.validateReceiver(domain,environment,method,object);
                }
            };
        }
        String text() throws Throwable {env.getDefaultBuffer().flush();return output.toString(StandardCharsets.UTF_8);}
        public void close() throws Exception {
            participant.close();execution.close();
            try {env.doFinal();}catch(Exception failure){throw failure;}catch(Error failure){throw failure;}catch(Throwable failure){throw new AssertionError(failure);}
        }
    }
    @Test public void inheritedScalarDefaultsPreparePurelyAndUseExactOverridingDestructor() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,strict,
                    "class LDParent {public int $y=7;function __destruct(){echo 'parent';}}class LD extends LDParent {public float $f=2;public ?string $s=null;public int $u;function __destruct(){echo $this->y;}}","LD")) {
                assertFalse(f.entity.isRuntimeStateInitialized(f.env));reject(()->PhpObjectShellVerifier.prepareLiteralDefaults(f.entity,f.env));
                var plan=PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity,f.env,f.entity.methodDestruct,f.protocol());
                assertFalse(f.entity.isRuntimeStateInitialized(f.env));assertEquals(64,plan.fingerprint().length());
                var provider=plan.initializeAndRegister(f.objects);assertTrue(f.entity.isRuntimeStateInitialized(f.env));assertSame(provider,plan.initializeAndRegister(f.objects));
                var allocation=f.objects.allocate(provider,f.env,plan.certifiedDefaults(f.objects));var cert=allocation.certificate();var node=cert.node();
                var table=cert.properties().table();assertEquals(7,table.getByScalar("y").toLong());assertEquals(2,table.getByScalar("f").toDouble(),0);
                assertTrue(table.getByScalar("s").isNull());assertTrue(table.getByScalar("u").isUndefined());assertTrue(table.getByScalar("y").hasPropertySources());
                allocation.close();assertEquals(1,f.calls);assertEquals("7",f.text());assertEquals(PhpOwnership.State.DISPOSED,node.state());
                assertFalse(table.getByScalar("y").hasPropertySources());assertEquals(0,f.domain.graph().liveNodes());
            }
    }
    @Test public void changedDefaultOrDestructorRejectsBeforeClassInitialization() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,false,"class LD {public int $y=7;function other(){}function __destruct(){echo 'no';}}","LD")) {
                var plan=PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity,f.env,f.entity.methodDestruct,f.protocol());var property=f.entity.findProperty("y");var raw=property.getDefaultValue();
                property.setDefaultValue(new LongMemory(8));try {reject(()->plan.initializeAndRegister(f.objects));}finally {property.setDefaultValue(raw);}
                assertFalse(f.entity.isRuntimeStateInitialized(f.env));var original=f.entity.methodDestruct;f.entity.methodDestruct=f.entity.findMethod("other");
                try {reject(()->plan.initializeAndRegister(f.objects));}finally {f.entity.methodDestruct=original;}
                assertFalse(f.entity.isRuntimeStateInitialized(f.env));assertEquals("",f.text());assertEquals(0,f.calls);assertEquals(0,f.domain.graph().liveNodes());
            }
    }
    @Test public void foreignProviderIsNotAdoptedOrInitializedByLifecyclePlan() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,false,"class LD {public int $y=7;function __destruct(){echo 'no';}}","LD")) {
                var protocol=f.protocol();var plan=PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity,f.env,f.entity.methodDestruct,protocol);
                f.objects.registerPropertiesProvider(f.entity,f.entity.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,v)->{},f.entity.methodDestruct,protocol);
                assertFalse(f.entity.isRuntimeStateInitialized(f.env));reject(()->plan.initializeAndRegister(f.objects));reject(()->plan.certifiedDefaults(f.objects));
                assertFalse(f.entity.isRuntimeStateInitialized(f.env));assertEquals("",f.text());assertEquals(0,f.calls);assertEquals(0,f.domain.graph().liveNodes());
            }
    }
    @Test public void foreignContextWrongMethodAndDistinctProtocolPlanStaySeparate() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,false,"class LD {public int $y=7;function other(){}function __destruct(){echo 'no';}}","LD")) {
                reject(()->PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity,f.env,f.entity.findMethod("other"),f.protocol()));
                var first=PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity,f.env,f.entity.methodDestruct,f.protocol());
                var second=PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity,f.env,f.entity.methodDestruct,f.protocol());
                var foreign=f.domain.participant(new Object());try(var execution=foreign.enter()){reject(()->first.initializeAndRegister(f.objects));}
                assertFalse(f.entity.isRuntimeStateInitialized(f.env));var provider=first.initializeAndRegister(f.objects);assertSame(provider,first.initializeAndRegister(f.objects));
                reject(()->second.initializeAndRegister(f.objects));reject(()->second.certifiedDefaults(f.objects));assertEquals(0,f.domain.graph().liveNodes());assertEquals(0,f.calls);
            }
    }
    @Test public void missingLoadedClassRejectsBeforeProtocolValidationEffects() throws Throwable {
        try(var f=new Fixture(false,false,false,"class LD {function __destruct(){echo 'no';}}","LD")) {
            var unknown=new ClassEntity(new Context(""));unknown.methodDestruct=f.entity.methodDestruct;int[] validations={0};
            var protocol=new PhpOwnershipObjects.CompleteDestructorProtocol() {
                public void validateMember(ClassEntity entity,MethodEntity method,Environment env) {validations[0]++;}
                public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,TraceInfo trace) {fail("Unknown class invoked");}
            };
            reject(()->PhpObjectShellVerifier.prepareLifecycleDefaults(unknown,f.env,unknown.methodDestruct,protocol));
            assertEquals(0,validations[0]);assertEquals("",f.text());assertEquals(0,f.domain.graph().liveNodes());
        }
    }
    @Test public void constructorHeapAndClassTypedDefaultsDoNotGainLifecycleAdmission() throws Throwable {
        String[] sources={
            "class LD {public int $y=7;function __construct(){echo 'ctor';}function __destruct(){echo 'd';}}",
            "class LD {public array $y=[1];function __destruct(){echo 'd';}}",
            "class Other {}class LD {public ?Other $y=null;function __destruct(){echo 'd';}}"
        };
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(String source:sources)
            try(var f=new Fixture(debug,dumped,false,source,"LD")) {
                reject(()->PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity,f.env,f.entity.methodDestruct,f.protocol()));
                assertFalse(f.entity.isRuntimeStateInitialized(f.env));assertEquals("",f.text());assertEquals(0,f.calls);assertEquals(0,f.domain.graph().liveNodes());
            }
    }
}
