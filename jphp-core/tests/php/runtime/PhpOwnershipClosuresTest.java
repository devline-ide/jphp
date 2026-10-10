package php.runtime;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.Closure;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.ClosureEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual generated shells/static tables and child destructors, with explicit host roots; no body-accounting claim. */
public class PhpOwnershipClosuresTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Environment env;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;
        final PhpOwnershipDomain.Execution execution;
        final PhpOwnershipArrays arrays;
        final PhpOwnershipObjects objects;
        final PhpOwnershipClosures closures;
        final ClosureEntity entity;
        Fixture(boolean debug,boolean dumped,String code) throws Throwable {
            var scope=newScope();scope.setDebugMode(debug);env=new Environment(scope,output);
            var context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if (dumped) {var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);
            entity=(ClosureEntity)module.include(env).toValue(ObjectMemory.class).value.getReflection();
            participant=domain.participant(env);execution=participant.enter();arrays=new PhpOwnershipArrays(domain.heap());objects=new PhpOwnershipObjects(domain.heap(),arrays);closures=new PhpOwnershipClosures(objects,arrays);
        }
        PhpOwnershipClosures.Provider provider() throws Throwable {return provider((e,v)->{});}
        PhpOwnershipClosures.Provider provider(PhpOwnershipObjects.CompletePropertiesProtocol protocol) throws Throwable {
            return closures.registerLexical(entity,entity.getNativeClass().getConstructor(Environment.class,ClassEntity.class,Memory.class,String.class,Memory[].class),protocol);
        }
        PhpOwnershipObjects.Allocation child(String name) throws Throwable {
            var clazz=env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);
            var provider=objects.registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,v)->{});
            var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for (var field:provider.properties()) defaults.put(field,scalar(domain,field.getDefaultValue(env).toImmutable()));
            return objects.allocate(provider,env,defaults);
        }
        String text() throws Throwable {env.getDefaultBuffer().flush();return output.toString(StandardCharsets.UTF_8);}
        public void close() throws Exception {participant.close();execution.close();try {env.doFinal();} catch (Exception|Error failure) {throw failure;} catch (Throwable failure) {throw new AssertionError(failure);}}
    }
    private PhpOwnershipValues.Binding scalar(PhpOwnershipDomain d,Memory value) {return PhpOwnershipValues.bindValue(d,value,null);}
    private void reject(Runnable work) {try {work.run();fail("expected rejected unmanaged operation");}catch (IllegalStateException|IllegalArgumentException|UnsupportedOperationException expected){}}
    @Test public void actualValueCaptureRetainsObjectUntilClosureDisposalSourceAndDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"class HCCapture {function __destruct(){echo 'capture;';}} $v=0;return function()use($v){return $v;};")) {
                var child=f.child("HCCapture");var childObject=child.object();var childNode=child.binding().node();
                var closure=f.closures.allocate(f.provider(),f.env,scalar(f.domain,Memory.NULL),null,List.of(child.binding()));
                child.close();assertEquals(1,childNode.incoming());assertSame(childObject,closure.closure().__invoke(f.env).toValue(ObjectMemory.class).value);
                var exported=closure.closure().getUses();exported[0]=Memory.NULL;
                assertSame(childObject,closure.closure().__invoke(f.env).toValue(ObjectMemory.class).value);
                closure.close();assertEquals(PhpOwnership.State.DISPOSED,childNode.state());assertEquals("capture;",f.text());
            }
    }
    @Test public void actualSelfIsOwnedEvenWhenFlagsDoNotReadThisSourceAndDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"class HCReceiver {function __destruct(){echo 'self;';}} return function(){return 7;};")) {
                var child=f.child("HCReceiver");var node=child.binding().node();var object=child.object();
                var closure=f.closures.allocate(f.provider(),f.env,child.binding(),"HCReceiver",List.of());child.close();
                assertFalse(f.entity.getOwnershipCapturePlan().usesThis());assertEquals(1,node.incoming());assertSame(object,closure.closure().getSelf().toValue(ObjectMemory.class).value);
                assertEquals(7,closure.closure().__invoke(f.env).toLong());closure.close();assertEquals("self;",f.text());
            }
    }
    @Test public void compiledStaticInitializationUsesActualAuthorityAndFreshValueCloneSourceAndDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"return function(){static $x=3;return $x;};")) {
                try (var source=f.closures.allocate(f.provider(),f.env,scalar(f.domain,Memory.NULL),null,List.of())) {
                    assertEquals(3,source.closure().__invoke(f.env).toLong());var storage=source.certificate().staticStorage();var carrier=storage.get("x");
                    assertSame(carrier,source.closure().getStatic("x"));assertSame(carrier,source.closure().getOrCreateStatic("x",new LongMemory(999)));
                    reject(()->carrier.assign(new LongMemory(5)));reject(()->storage.clear());reject(()->storage.entrySet().iterator().remove());
                    storage.slot("x").payloadPublisher().assign(scalar(f.domain,new LongMemory(5)));
                    try (var copy=source.certificate().cloneValues(f.env,scalar(f.domain,Memory.NULL),null)) {
                        assertNotSame(source.closure(),copy.closure());assertNotSame(source.binding().node(),copy.binding().node());
                        assertNotSame(carrier,copy.certificate().staticStorage().get("x"));
                        copy.certificate().staticStorage().slot("x").payloadPublisher().assign(scalar(f.domain,new LongMemory(9)));
                        assertEquals(5,source.closure().__invoke(f.env).toLong());assertEquals(9,copy.closure().__invoke(f.env).toLong());
                    }
                }
            }
    }
    @Test public void canonicalCaptureAndStaticPayloadsHaveOneAuthorityAndCloneRejectsBeforeFactorySourceAndDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"$r=0;return function()use(&$r){static $x=3;return $r;};")) {
                int[] factories={0};var provider=f.provider((e,v)->factories[0]++);
                try (var cell=f.arrays.allocateCell(scalar(f.domain,Memory.CONST_INT_0));var closure=f.closures.allocate(provider,f.env,scalar(f.domain,Memory.NULL),null,List.of(cell.binding()))) {
                    assertSame(cell.cell(),closure.closure().getUses()[0]);assertEquals(0,closure.closure().__invoke(f.env).toLong());
                    ((PhpOwnershipArrays.Entry)f.arrays.certificate(cell.cell()).value()).assign(scalar(f.domain,new LongMemory(9)));assertEquals(9,closure.closure().__invoke(f.env).toLong());
                    try {closure.certificate().cloneValues(f.env,scalar(f.domain,Memory.NULL),null);fail("reference clone guessed owner counts");}catch (IllegalStateException expected){}
                    assertEquals(1,factories[0]);var staticSlot=closure.certificate().staticStorage().slot("x");
                    try (var promotion=staticSlot.preparePromotion(staticSlot.ownerPublisher().current())) {promotion.store();promotion.commit();}
                    staticSlot.payloadPublisher().assign(scalar(f.domain,new LongMemory(7)));assertEquals(7,closure.closure().getStatic("x").toLong());
                }
            }
    }
    @Test public void arrayStaticCloneSeparatesOnlyItsWriterAddressSourceAndDump() throws Throwable {
        for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(false,dumped,"return function(){static $a=[1];return $a;};");var array=f.arrays.allocate(false)) {
                array.append(scalar(f.domain,Memory.CONST_INT_1));
                try (var source=f.closures.allocate(f.provider(),f.env,scalar(f.domain,Memory.NULL),null,List.of())) {
                    source.certificate().staticStorage().getOrCreate("a",array.binding());
                    try (var copy=source.certificate().cloneValues(f.env,scalar(f.domain,Memory.NULL),null)) {
                        var slot=copy.certificate().staticStorage().slot("a");var carrier=slot.ownerPublisher().carrier();
                        try (var write=f.arrays.prepareWrite(slot.ownerPublisher(),slot.ownerPublisher().current())) {
                            assertTrue(write.separated());try (var p=(PhpOwnershipArrays.RawPublication)write.preparePublication()) {carrier.publishOwnershipRaw(p);p.commit();}
                            write.entry(Memory.CONST_INT_0).assign(scalar(f.domain,new LongMemory(2)));
                        }
                        assertEquals(1,source.closure().__invoke(f.env).toValue(ArrayMemory.class).getByScalar(Memory.CONST_INT_0).toLong());
                        assertEquals(2,copy.closure().__invoke(f.env).toValue(ArrayMemory.class).getByScalar(Memory.CONST_INT_0).toLong());
                    }
                }
            }
    }
    @Test public void foreignAndUnknownShellsRejectBeforeFactoryEffectsSourceAndDump() throws Throwable {
        for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(false,dumped,"$v=0;return function()use($v){return $v;};");var foreign=new Fixture(false,dumped,"class HCForeign {} return function(){return 0;};")) {
                int[] effects={0};var provider=f.provider((e,v)->effects[0]++);var child=foreign.child("HCForeign");
                try {f.closures.allocate(provider,f.env,scalar(f.domain,Memory.NULL),null,List.of(child.binding()));fail("foreign capture admitted");}catch (IllegalArgumentException expected){}
                assertEquals(0,effects[0]);child.close();
                var original=f.entity.getNativeClass();UntrustedClosure.constructors=0;
                try {f.entity.setNativeClazz(UntrustedClosure.class);reject(()->{
                    try {f.closures.registerLexical(f.entity,UntrustedClosure.class.getConstructor(Environment.class,ClassEntity.class,Memory.class,String.class,Memory[].class),(e,v)->effects[0]++);}
                    catch (ReflectiveOperationException failure) {throw new AssertionError(failure);}
                });}finally {f.entity.setNativeClazz(original);}
                assertEquals(0,UntrustedClosure.constructors);assertEquals(0,effects[0]);
                try (var cell=f.arrays.allocateCell(scalar(f.domain,Memory.CONST_INT_0))) {
                    try {f.closures.allocate(provider,f.env,scalar(f.domain,Memory.NULL),null,List.of(cell.binding()));fail("CELL guessed for VALUE capture");}catch (IllegalArgumentException expected){}
                    assertEquals(0,effects[0]);
                }
            }
    }
    @Test public void actualClosureStaticCycleCollectsAndOwnedCallViewDoesNotShallowCopyCertificateSourceAndDump() throws Throwable {
        for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(false,dumped,"return function(){return 1;};")) {
                var allocation=f.closures.allocate(f.provider(),f.env,scalar(f.domain,Memory.NULL),null,List.of());var certificate=allocation.certificate();var node=allocation.binding().node();
                certificate.staticStorage().getOrCreate("cycle",allocation.binding());
                try {allocation.closure().call(f.env,new ObjectMemory(allocation.closure()));fail("owned call copied stamp");}catch (IllegalStateException expected){}
                allocation.close();assertEquals(PhpOwnership.State.LIVE,node.state());assertTrue(f.domain.collectCycles()>=3);assertEquals(PhpOwnership.State.DISPOSED,node.state());
                reject(()->certificate.staticStorage());
            }
    }
    public static final class UntrustedClosure extends Closure {
        static int constructors;
        public UntrustedClosure(Environment env,ClassEntity entity,Memory self,String scope,Memory[] uses) {super(env,entity,self,scope,uses);constructors++;}
        public Memory __invoke(Environment env,Memory... args) {return Memory.NULL;}
        public Memory getOrCreateStatic(String name) {return Memory.NULL;}
    }
}
