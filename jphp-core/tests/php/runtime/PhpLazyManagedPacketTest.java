package php.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.helper.PhpObjectShellVerifier;
import static org.junit.Assert.*;

/** Actual managed native packets and transactional scalar defaults/reset. Wider managed producers remain outside this phase. */
public class PhpLazyManagedPacketTest {
    private final class Fixture implements AutoCloseable {
        final Environment env;
        final ModuleEntity module;
        final TraceInfo trace;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;
        final PhpOwnershipExecution.Session session;
        final PhpOwnershipExecution.Scope active;
        Fixture(boolean debug,boolean dumped)throws Throwable {
            var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
            env=new Environment(scope,new ByteArrayOutputStream());
            var context=new Context("class ManagedLazySubject {public int $x=7;public int $y=8;} class ManagedLazyInitializer {public function __invoke($o){}} class ManagedLazyFactory {public int $x=7;public function __invoke($o){return $this;}} class ManagedLazyBad {public function __invoke($o){return 1;}}");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);trace=new TraceInfo(context);
            var compiled=new JvmCompiler(env,context).compile(false);
            if(dumped) {
                var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                dumper.save(compiled,bytes);compiled=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            module=compiled;env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
            for(var entity:module.getClasses())entity.initializeRuntimeState(env);
            participant=domain.participant(env);session=new PhpOwnershipExecution.Session(participant,env);active=session.enter();
        }
        ClassEntity clazz(String name){return env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);}
        PhpOwnershipObjects.Allocation reflection(String name)throws Throwable {
            var entity=clazz(name);var provider=PhpOwnershipReflectionCalls.registerShell(session.objects(),env,entity);
            var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for(var property:provider.properties())defaults.put(property,session.value(Memory.UNDEFINED));
            return session.objects().allocate(provider,env,defaults);
        }
        final java.util.Set<php.runtime.reflection.MethodEntity> admitted=java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        PhpOwnershipObjects.Allocation initializer()throws Throwable {return object("ManagedLazyInitializer");}
        PhpOwnershipObjects.Allocation object(String name)throws Throwable {
            var entity=clazz(name);
            PhpOwnershipObjects.Provider provider;
            try{provider=session.objects().provider(entity);}catch(IllegalArgumentException absent){provider=PhpObjectShellVerifier.register(session.objects(),env,entity);}
            var method=entity.findMethod("__invoke");var member=method.getNativeMethod();byte[] bytes=entity.getData();
            if(!admitted.contains(method)) {
                var plan=OwnershipFrameCompiler.read(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
                assertNotNull(plan);assertEquals(plan.unsupported().toString(),java.util.List.of(),plan.unsupported());
                var runtimePlan=plan.runtimePlan();
                session.registerGenerated(method,member,bytes,runtimePlan,(actualPlan,actualMember,actualBytes)->{
                    assertSame(runtimePlan,actualPlan);assertEquals(member,actualMember);assertArrayEquals(bytes,actualBytes);
                    assertSame(method,entity.findMethod("__invoke"));
                });admitted.add(method);
            }
            var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for(var property:provider.properties())defaults.put(property,session.value(property.getDefaultValue(env)));
            return session.objects().allocate(provider,env,defaults);
        }
        Memory invoke(BaseObject receiver,PhpOwnershipValues.Binding binding,String name,PhpOwnershipExecution.CallRoots roots)throws Throwable {
            roots.stageReceiver(binding);return session.invokeCheckedMethod(receiver.getReflection().findMethod(name.toLowerCase()),receiver,trace,roots,true);
        }
        void construct(BaseObject receiver,PhpOwnershipValues.Binding binding,Memory...args)throws Throwable {
            try(var roots=session.newCallRoots()) {
                for(var argument:args)roots.send(session.value(argument));
                invoke(receiver,binding,"__construct",roots);
            }
        }
        @Override public void close()throws Exception {
            Throwable failure=null;
            try{env.doFinal();}catch(Throwable error){failure=error;}
            try{active.close();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            try(var execution=participant.enter()){participant.close();}
            catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            if(failure!=null)PhpLazyManagedPacketTest.<Exception>raise(failure);
        }
    }
    @Test public void nativeReflectionConstructorMetadataUsesActualCertifiedSlotsSourceAndPhb()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb)) {
            try(var allocation=f.reflection("ReflectionClass")) {
                var reflector=(BaseObject)allocation.object();var binding=allocation.binding();
                f.construct(reflector,binding,new StringMemory("ManagedLazySubject"));
                assertEquals("ManagedLazySubject",reflector.getOwnershipObject().properties()
                        .entry(reflector.getOwnershipProperties().getByScalar("name")).payloadPublisher().current().binding().rawValue().toString());
                try(var roots=f.session.newCallRoots()) {
                    assertEquals("ManagedLazySubject",f.invoke(reflector,binding,"getName",roots).toString());
                    assertNull(roots.result().node());
                }
            }
            try(var allocation=f.reflection("ReflectionProperty")) {
                var property=(BaseObject)allocation.object();var binding=allocation.binding();
                f.construct(property,binding,new StringMemory("ManagedLazySubject"),new StringMemory("x"));
                property.getOwnershipObject().properties().validateAll();
                try(var roots=f.session.newCallRoots()){assertEquals("x",f.invoke(property,binding,"getName",roots).toString());}
            }
            assertNull(PhpOwnershipExecution.currentEntry(f.env));
        }
    }
    @Test public void lazyInitializerSendBecomesActualShellEdgeAndRealResultThenFinalReleaseSourceAndPhb()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb);
                var reflection=f.reflection("ReflectionClass");var callback=f.initializer()) {
            var reflector=(BaseObject)reflection.object();var reflectionBinding=reflection.binding();
            f.construct(reflector,reflectionBinding,new StringMemory("ManagedLazySubject"));
            var callbackBinding=callback.binding();var callbackNode=callbackBinding.node();
            try(var creation=f.session.newCallRoots()) {
                creation.send(callbackBinding);
                var result=f.invoke(reflector,reflectionBinding,"newLazyGhost",creation);
                var shell=((ObjectMemory)result).value;var shellBinding=creation.result();var node=shellBinding.node();
                assertSame(shell,((ObjectMemory)shellBinding.rawValue()).value);
                assertTrue(PhpLazyObjects.uninitialized(shell));
                assertTrue(((BaseObject)shell).getOwnershipProperties().getByScalar("x").isUndefined());
                try(var returnedOwner=shellBinding.host()) {creation.close();
                callback.close();assertEquals(1,callbackNode.incoming());
                try(var query=f.session.newCallRoots()) {
                    query.send(shellBinding);
                    var returned=f.invoke(reflector,reflectionBinding,"getLazyInitializer",query);
                    assertSame(((ObjectMemory)callbackBinding.rawValue()).value,((ObjectMemory)returned).value);
                    assertSame(callbackNode,query.result().node());assertEquals(2,callbackNode.incoming());
                }
                assertEquals(1,callbackNode.incoming());
                Object tableIdentity=((BaseObject)shell).getOwnershipProperties().getStorageIdentity();
                try(var initialize=f.session.newCallRoots()) {
                    initialize.send(shellBinding);
                    assertSame(shell,((ObjectMemory)f.invoke(reflector,reflectionBinding,"initializeLazyObject",initialize)).value);
                    assertSame(node,initialize.result().node());
                }
                assertFalse(PhpLazyObjects.uninitialized(shell));assertEquals(PhpOwnership.State.DISPOSED,callbackNode.state());
                assertSame(tableIdentity,((BaseObject)shell).getOwnershipProperties().getStorageIdentity());
                assertEquals(7,((BaseObject)shell).getOwnershipProperties().getByScalar("x").toLong());
                assertEquals(8,((BaseObject)shell).getOwnershipProperties().getByScalar("y").toLong());
                try(var next=f.initializer();var reset=f.session.newCallRoots()) {
                    var nextNode=next.binding().node();reset.send(shellBinding);reset.send(next.binding());
                    f.invoke(reflector,reflectionBinding,"resetAsLazyGhost",reset);reset.close();next.close();
                    assertTrue(PhpLazyObjects.uninitialized(shell));assertEquals(1,nextNode.incoming());
                    assertSame(tableIdentity,((BaseObject)shell).getOwnershipProperties().getStorageIdentity());
                    assertTrue(((BaseObject)shell).getOwnershipProperties().getByScalar("x").isUndefined());
                    try(var mark=f.session.newCallRoots()){mark.send(shellBinding);f.invoke(reflector,reflectionBinding,"markLazyObjectAsInitialized",mark);}
                    assertFalse(PhpLazyObjects.uninitialized(shell));assertEquals(PhpOwnership.State.DISPOSED,nextNode.state());
                }
                ((BaseObject)shell).getOwnershipObject().properties().validateAll();
                returnedOwner.close();assertEquals(PhpOwnership.State.DISPOSED,node.state());
                assertEquals(PhpOwnership.State.DISPOSED,callbackNode.state());assertSame(Memory.NULL,PhpLazyObjects.initializer(shell));
                }
            }
            assertNull(PhpOwnershipExecution.currentEntry(f.env));
        }
    }
    @Test public void duplicateNativeMethodEntityCannotBorrowCanonicalReflectionAdmissionSourceAndPhb()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb);var reflection=f.reflection("ReflectionClass")) {
            var reflector=(BaseObject)reflection.object();var binding=reflection.binding();f.construct(reflector,binding,new StringMemory("ManagedLazySubject"));
            var actual=reflector.getReflection().findMethod("getname");var duplicate=new php.runtime.reflection.MethodEntity(actual);
            duplicate.setName(actual.getName());duplicate.setClazz(actual.getClazz());duplicate.setNativeMethod(actual.getNativeMethod());
            try(var roots=f.session.newCallRoots()) {
                roots.stageReceiver(binding);
                try{f.session.invokeCheckedMethod(duplicate,reflector,f.trace,roots,false);fail("duplicate MethodEntity borrowed real native admission");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("actual registered MethodEntity"));}
                assertNull(roots.result());assertNull(PhpOwnershipExecution.currentEntry(f.env));
            }
            try(var roots=f.session.newCallRoots()){assertEquals("ManagedLazySubject",f.invoke(reflector,binding,"getName",roots).toString());}
        }
    }
    @Test public void factoryResultKeepsActualTargetAfterCallbackPacketClosesAndResetReleasesItSourceAndPhb()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb);
                var reflection=f.reflection("ReflectionClass");var factory=f.object("ManagedLazyFactory")) {
            var reflector=(BaseObject)reflection.object();var reflectionBinding=reflection.binding();f.construct(reflector,reflectionBinding,new StringMemory("ManagedLazyFactory"));
            var real=factory.object();var realNode=factory.binding().node();
            try(var roots=f.session.newCallRoots()) {
                try { roots.stageReceiver(f.session.value(new ObjectMemory(new BaseObject(f.env,f.clazz("ManagedLazyFactory")) {}))); fail("unallocated receiver admitted"); }
                catch(IllegalArgumentException expected) { assertNull(roots.receiver()); }
            }
            try(var foreign=new Fixture(debug,phb);var other=foreign.object("ManagedLazyFactory");var roots=f.session.newCallRoots()) {
                try { roots.stageReceiver(other.binding()); fail("foreign-domain receiver admitted"); }
                catch(IllegalArgumentException expected) { assertNull(roots.receiver()); }
            }
            try(var creation=f.session.newCallRoots()) {
                creation.send(factory.binding());var proxy=(BaseObject)((ObjectMemory)f.invoke(reflector,reflectionBinding,"newLazyProxy",creation)).value;
                var binding=creation.result();Object identity=proxy.getOwnershipProperties().getStorageIdentity();
                try(var returnedOwner=binding.host()) {
                    creation.close();factory.close();assertEquals(1,realNode.incoming());
                    try(var initialize=f.session.newCallRoots()) {
                        initialize.send(binding);assertSame(real,((ObjectMemory)f.invoke(reflector,reflectionBinding,"initializeLazyObject",initialize)).value);
                        assertSame(realNode,initialize.result().node());
                    }
                    assertEquals(1,realNode.incoming());assertSame(proxy,((ObjectMemory)binding.rawValue()).value);
                    try(var call=f.session.newCallRoots()) {
                        call.send(f.session.value(Memory.NULL));
                        assertSame(proxy,((ObjectMemory)f.invoke(proxy,binding,"__invoke",call)).value);
                        assertSame(binding.node(),call.result().node());
                    }
                    try(var mark=f.session.newCallRoots()) {
                        mark.send(binding);assertSame(proxy,((ObjectMemory)f.invoke(reflector,reflectionBinding,"markLazyObjectAsInitialized",mark)).value);
                        assertSame(binding.node(),mark.result().node());
                    }
                    try(var next=f.initializer();var reset=f.session.newCallRoots()) {
                        var nextNode=next.binding().node();reset.send(binding);reset.send(next.binding());
                        f.invoke(reflector,reflectionBinding,"resetAsLazyGhost",reset);reset.close();next.close();
                        assertEquals(PhpOwnership.State.DISPOSED,realNode.state());assertEquals(1,nextNode.incoming());
                        assertTrue(PhpLazyObjects.uninitialized(proxy));assertSame(identity,proxy.getOwnershipProperties().getStorageIdentity());
                        try(var initialize=f.session.newCallRoots()){initialize.send(binding);f.invoke(reflector,reflectionBinding,"initializeLazyObject",initialize);}
                        assertEquals(PhpOwnership.State.DISPOSED,nextNode.state());
                    }
                }
            }
        }
    }
    @Test public void failedInitializerRestoresUndefinedFieldsAndCanRetryWithActualCallbackOwnerSourceAndPhb()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb);
                var reflection=f.reflection("ReflectionClass");var bad=f.object("ManagedLazyBad")) {
            var reflector=(BaseObject)reflection.object();var reflectionBinding=reflection.binding();f.construct(reflector,reflectionBinding,new StringMemory("ManagedLazySubject"));
            var badNode=bad.binding().node();
            try(var creation=f.session.newCallRoots()) {
                creation.send(bad.binding());var shell=(BaseObject)((ObjectMemory)f.invoke(reflector,reflectionBinding,"newLazyGhost",creation)).value;
                var binding=creation.result();Object identity=shell.getOwnershipProperties().getStorageIdentity();
                try(var returnedOwner=binding.host()) {
                    creation.close();bad.close();assertEquals(1,badNode.incoming());
                    for(int attempt=0;attempt<2;attempt++)try(var initialize=f.session.newCallRoots()) {
                        initialize.send(binding);
                        try{f.invoke(reflector,reflectionBinding,"initializeLazyObject",initialize);fail("invalid ghost return accepted");}
                        catch(php.runtime.lang.exception.BaseTypeError expected){assertTrue(expected.getMessage(f.env).toString().contains("must return null or void"));}
                        assertTrue(PhpLazyObjects.uninitialized(shell));assertSame(identity,shell.getOwnershipProperties().getStorageIdentity());
                        assertTrue(shell.getOwnershipProperties().getByScalar("x").isUndefined());assertTrue(shell.getOwnershipProperties().getByScalar("y").isUndefined());
                        shell.getOwnershipObject().properties().validateAll();assertEquals(1,badNode.incoming());
                    }
                }
                assertEquals(PhpOwnership.State.DISPOSED,badNode.state());
            }
        }
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable>void raise(Throwable failure)throws T {throw(T)failure;}
}
