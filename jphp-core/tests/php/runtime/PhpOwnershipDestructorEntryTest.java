package php.runtime;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.invoke.PhpObjectLifecycle;
import php.runtime.lang.IObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual generated destructor bodies through an explicit provider; ordinary body admission is separate. */
public class PhpOwnershipDestructorEntryTest extends JvmCompilerCase {
    @FunctionalInterface private interface Work {void run() throws Throwable;}
    private void reject(Work work) throws Throwable {
        try {work.run();fail("Expected lifecycle origin rejection");}
        catch (IllegalArgumentException | IllegalStateException expected) {}
    }
    private final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Environment env;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;
        final PhpOwnershipDomain.Execution execution;
        final PhpOwnershipObjects objects;
        Fixture(boolean debug,boolean dumped,boolean strict,String body) throws Throwable {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            var context=new Context((strict?"declare(strict_types=1);":"")+body);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(dumped) {
                var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
                dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            participant=domain.participant(env);execution=participant.enter();
            objects=new PhpOwnershipObjects(domain.heap(),new PhpOwnershipArrays(domain.heap()));
        }
        ClassEntity clazz() {return env.fetchClass("EDtor",true,PhpLanguageProfile.PHP_8_5);}
        PhpOwnershipObjects.Provider provider(PhpOwnershipObjects.CompleteDestructorProtocol protocol) throws Throwable {
            var entity=clazz();
            return objects.registerPropertiesProvider(entity,entity.getNativeClass().getConstructor(Environment.class,ClassEntity.class),
                    (actual,environment)->{assertSame(entity,actual);assertSame(env,environment);},entity.methodDestruct,protocol);
        }
        PhpOwnershipObjects.Allocation allocate(PhpOwnershipObjects.Provider provider) throws Throwable {
            var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for(var property:provider.properties())defaults.put(property,PhpOwnershipValues.bindValue(domain,property.getDefaultValue(env).toImmutable(),null));
            return objects.allocate(provider,env,defaults);
        }
        String text() throws Throwable {env.getDefaultBuffer().flush();return output.toString(StandardCharsets.UTF_8);}
        public void close() throws Exception {
            participant.close();execution.close();
            try {env.doFinal();}catch(Exception failure){throw failure;}catch(Error failure){throw failure;}catch(Throwable failure){throw new AssertionError(failure);}
        }
    }
    private PhpOwnershipObjects.CompleteDestructorProtocol protocol(Fixture f,Work callback) {
        return new PhpOwnershipObjects.CompleteDestructorProtocol() {
            public void validateMember(ClassEntity entity,MethodEntity method,Environment env) {
                assertSame(f.clazz(),entity);assertSame(entity.methodDestruct,method);assertSame(f.env,env);
            }
            public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,TraceInfo trace) throws Throwable {callback.run();}
        };
    }
    @Test public void actualGeneratedBodyBorrowsFinalizingReceiverAndExpiresAfterCallback() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,strict,"class EDtor {public int $v=7;function __destruct(){echo $this->v;}}")) {
                PhpOwnershipObjects.ExecutionEntry[] saved={null};PhpOwnershipObjects.ObjectCertificate[] cert={null};int[] calls={0};
                var provider=f.provider(new PhpOwnershipObjects.CompleteDestructorProtocol() {
                    public void validateMember(ClassEntity entity,MethodEntity method,Environment env) {assertSame(f.clazz(),entity);assertSame(entity.methodDestruct,method);assertSame(f.env,env);}
                    public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,TraceInfo trace) throws Throwable {
                        saved[0]=entry;calls[0]++;var method=entry.method();var object=entry.receiver();
                        assertTrue(object.isFinalized());assertEquals(PhpOwnership.State.FINALIZING,cert[0].node().state());assertEquals(0,cert[0].node().incoming());
                        assertEquals(1,cert[0].properties().node().incoming());assertTrue(cert[0].properties().table().getByScalar("v").hasPropertySources());
                        reject(()->entry.validateReceiver(f.domain,env,method,object));
                        assertSame(object,entry.consume(f.domain,env,method));entry.validateReceiver(f.domain,env,method,object);
                        reject(()->entry.consume(f.domain,env,method));reject(()->entry.dispatch(env,trace));
                        assertFalse(PhpObjectLifecycle.destruct(object,env,PhpObjectLifecycle.Reason.OWNERSHIP_RELEASE));
                        method.invokeDynamic(object,env,trace);assertEquals(0,cert[0].node().incoming());
                    }
                });
                var allocation=f.allocate(provider);cert[0]=allocation.certificate();var node=cert[0].node();var carrier=cert[0].properties().table().getByScalar("v");int depth=f.env.getCallStack().getTop();
                allocation.close();assertEquals(1,calls[0]);assertEquals("7",f.text());assertEquals(depth,f.env.getCallStack().getTop());
                assertEquals(PhpOwnership.State.DISPOSED,node.state());assertFalse(carrier.hasPropertySources());assertEquals(0,f.domain.graph().liveNodes());
                reject(()->saved[0].receiver());reject(()->saved[0].consume(f.domain,f.env,f.clazz().methodDestruct));
            }
    }
    @Test public void exactReceiverRejectsForeignDomainEnvironmentMethodAndObjectWithoutEffects() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,strict,"class EDtor {public int $v=3;function other(){}function __destruct(){echo 'd';}}")) {
                var foreign=new Environment(f.env.scope,new ByteArrayOutputStream());
                PhpOwnershipObjects.Allocation[] other={null};int[] calls={0};
                try {
                    var provider=f.provider(new PhpOwnershipObjects.CompleteDestructorProtocol() {
                        public void validateMember(ClassEntity entity,MethodEntity method,Environment env) {assertSame(f.clazz(),entity);assertSame(entity.methodDestruct,method);assertSame(f.env,env);}
                        public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,TraceInfo trace) throws Throwable {
                            calls[0]++;var method=entry.method();var object=entry.receiver();int depth=foreign.getCallStack().getTop();
                            reject(()->entry.consume(new PhpOwnershipDomain(),env,method));reject(()->entry.consume(f.domain,foreign,method));
                            reject(()->entry.consume(f.domain,env,f.clazz().findMethod("other")));
                            reject(()->PhpObjectLifecycle.destruct(entry,foreign));assertEquals(depth,foreign.getCallStack().getTop());
                            assertSame(object,entry.consume(f.domain,env,method));
                            if(other[0]!=null)reject(()->entry.validateReceiver(f.domain,env,method,other[0].object()));
                            method.invokeDynamic(object,env,trace);
                        }
                    });
                    var first=f.allocate(provider);other[0]=f.allocate(provider);first.close();assertEquals(1,calls[0]);assertEquals("d",f.text());
                    var last=other[0];other[0]=null;last.close();assertEquals(2,calls[0]);assertEquals("dd",f.text());assertEquals(0,f.domain.graph().liveNodes());
                } finally {foreign.doFinal();}
            }
    }
    @Test public void realThrowingBodyExpiresBorrowAndReleasesPhysicalTypeSources() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,strict,"class EDtor {public int $v=3;function __destruct(){echo $this->v;throw new Exception('cleanup');}}")) {
                PhpOwnershipObjects.ExecutionEntry[] saved={null};
                var provider=f.provider(new PhpOwnershipObjects.CompleteDestructorProtocol() {
                    public void validateMember(ClassEntity entity,MethodEntity method,Environment env) {assertSame(entity.methodDestruct,method);}
                    public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,TraceInfo trace) throws Throwable {
                        saved[0]=entry;var method=entry.method();var object=entry.consume(f.domain,env,method);method.invokeDynamic(object,env,trace);
                    }
                });
                var allocation=f.allocate(provider);var cert=allocation.certificate();var node=cert.node();var carrier=cert.properties().table().getByScalar("v");int depth=f.env.getCallStack().getTop();
                try {allocation.close();fail("Lost actual destructor failure");}catch(php.runtime.lang.BaseException expected){assertEquals("cleanup",expected.getMessage(f.env).toString());}
                assertEquals("3",f.text());assertEquals(depth,f.env.getCallStack().getTop());assertEquals(PhpOwnership.State.DISPOSED,node.state());
                assertFalse(carrier.hasPropertySources());assertEquals(0,f.domain.graph().liveNodes());reject(()->saved[0].receiver());
            }
    }
    @Test public void genuineResurrectionIsExplicitOwnerAndDoesNotExtendBorrowTicket() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,strict,"class EDtor {public int $v=3;function __destruct(){echo 'keep';}}")) {
                PhpOwnershipObjects.ExecutionEntry[] ticket={null};PhpOwnershipObjects.ObjectCertificate[] cert={null};PhpOwnershipDomain.OwnedValue[] saved={null};int[] calls={0};
                var provider=f.provider(new PhpOwnershipObjects.CompleteDestructorProtocol() {
                    public void validateMember(ClassEntity entity,MethodEntity method,Environment env) {assertSame(entity.methodDestruct,method);}
                    public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,TraceInfo trace) throws Throwable {
                        ticket[0]=entry;calls[0]++;var method=entry.method();var object=entry.consume(f.domain,env,method);
                        assertEquals(0,cert[0].node().incoming());saved[0]=f.domain.hostValue(cert[0].node(),new ObjectMemory(object));assertEquals(1,cert[0].node().incoming());
                        method.invokeDynamic(object,env,trace);entry.validateReceiver(f.domain,env,method,object);
                    }
                });
                var allocation=f.allocate(provider);cert[0]=allocation.certificate();var node=cert[0].node();var carrier=cert[0].properties().table().getByScalar("v");allocation.close();
                assertEquals(PhpOwnership.State.LIVE,node.state());assertTrue(carrier.hasPropertySources());reject(()->ticket[0].receiver());
                saved[0].close();assertEquals(1,calls[0]);assertEquals("keep",f.text());assertEquals(PhpOwnership.State.DISPOSED,node.state());
                assertFalse(carrier.hasPropertySources());assertEquals(0,f.domain.graph().liveNodes());
            }
    }
    @Test public void wrongOrChangedDestructorRejectsBeforeAllocationAndBody() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,strict,"class EDtor {public int $v=3;function other(){}function __destruct(){echo 'wrong';}}")) {
                var entity=f.clazz();var constructor=entity.getNativeClass().getConstructor(Environment.class,ClassEntity.class);int[] effects={0};
                var lifecycle=protocol(f,()->{effects[0]++;});
                reject(()->f.objects.registerPropertiesProvider(entity,constructor,(e,v)->{},entity.findMethod("other"),lifecycle));
                var provider=f.provider(lifecycle);var original=entity.methodDestruct;entity.methodDestruct=entity.findMethod("other");
                try {reject(()->f.allocate(provider));}finally {entity.methodDestruct=original;}
                assertEquals(0,effects[0]);assertEquals("",f.text());assertEquals(0,f.domain.graph().liveNodes());
            }
    }
    @Test public void changedPhysicalReflectionRejectsConsumedReceiverBeforeBody() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,false,"class EDtor {public int $v=3;function __destruct(){echo 'wrong';}}")) {
                PhpOwnershipObjects.ExecutionEntry[] saved={null};int[] rejected={0};
                var provider=f.provider(new PhpOwnershipObjects.CompleteDestructorProtocol() {
                    public void validateMember(ClassEntity entity,MethodEntity method,Environment env) {assertSame(entity.methodDestruct,method);}
                    public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,TraceInfo trace) throws Throwable {
                        saved[0]=entry;var method=entry.method();var object=entry.consume(f.domain,env,method);
                        entry.validateReceiver(f.domain,env,method,object);object.setAsMock();assertNull(object.getReflection());
                        reject(()->entry.validateReceiver(f.domain,env,method,object));rejected[0]++;
                        reject(()->entry.receiver());
                    }
                });
                var allocation=f.allocate(provider);var cert=allocation.certificate();var node=cert.node();var carrier=cert.properties().table().getByScalar("v");
                allocation.close();assertEquals(1,rejected[0]);assertEquals("",f.text());assertEquals(PhpOwnership.State.DISPOSED,node.state());
                assertFalse(carrier.hasPropertySources());assertEquals(0,f.domain.graph().liveNodes());reject(()->saved[0].receiver());
            }
    }
    @Test public void unconsumedRegisteredBodyFailsAndStillExpiresTicket() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,false,"class EDtor {public int $v=3;function __destruct(){echo 'no';}}")) {
                PhpOwnershipObjects.ExecutionEntry[] saved={null};
                var provider=f.provider(new PhpOwnershipObjects.CompleteDestructorProtocol() {
                    public void validateMember(ClassEntity entity,MethodEntity method,Environment env) {}
                    public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,TraceInfo trace) {saved[0]=entry;}
                });
                var allocation=f.allocate(provider);var cert=allocation.certificate();var node=cert.node();var carrier=cert.properties().table().getByScalar("v");
                reject(()->allocation.close());assertEquals("",f.text());assertEquals(PhpOwnership.State.DISPOSED,node.state());
                assertFalse(carrier.hasPropertySources());assertEquals(0,f.domain.graph().liveNodes());reject(()->saved[0].receiver());
            }
    }
}
