package php.runtime;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
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

/** Real compiled classes, actual property carriers and production destructor callbacks; no implicit execution accounting. */
public class PhpOwnershipObjectsTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output;
        final Environment env;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;
        final PhpOwnershipDomain.Execution execution;
        final PhpOwnershipArrays arrays;
        final PhpOwnershipObjects objects;
        Fixture(boolean debug,boolean dumped,String source) throws Throwable {this(debug,dumped,source,new ByteArrayOutputStream());}
        Fixture(boolean debug,boolean dumped,String source,ByteArrayOutputStream output) throws Throwable {this(debug,dumped,source,output,PhpLanguageProfile.PHP_8_5);}
        Fixture(boolean debug,boolean dumped,String source,ByteArrayOutputStream output,PhpLanguageProfile profile) throws Throwable {
            this.output=output;var scope=newScope();scope.setDebugMode(debug);env=new Environment(scope,output);
            var context=new Context(source);context.setPhpLanguageProfile(profile);
            var module=new JvmCompiler(env,context).compile(false);
            if (dumped) {var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
            participant=domain.participant(env);execution=participant.enter();arrays=new PhpOwnershipArrays(domain.heap());objects=new PhpOwnershipObjects(domain.heap(),arrays);
        }
        ClassEntity clazz(String name) {return env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);}
        PhpOwnershipObjects.Provider provider(String name) throws Throwable {
            var entity=clazz(name);return objects.registerPropertiesProvider(entity,entity.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,v)->{});
        }
        Map<PropertyEntity,PhpOwnershipValues.Binding> defaults(PhpOwnershipObjects.Provider provider) {
            var result=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for (var property : provider.properties()) result.put(property,scalar(domain,property.getDefaultValue(env).toImmutable()));
            return result;
        }
        String text() throws Throwable {env.getDefaultBuffer().flush();return output.toString(StandardCharsets.UTF_8);}
        public void close() throws Exception {participant.close();execution.close();try {env.doFinal();} catch (Exception failure) {throw failure;} catch (Error failure) {throw failure;} catch (Throwable failure) {throw new AssertionError(failure);}}
    }
    private PhpOwnershipValues.Binding scalar(PhpOwnershipDomain domain,Memory value) {return PhpOwnershipValues.bindValue(domain,value,null);}
    private void reject(Runnable work) {try {work.run();fail("expected rejection");} catch (IllegalArgumentException | IllegalStateException expected) {}}
    private void assign(PhpOwnershipArrays.PhysicalSlot slot,PhpOwnershipValues.Binding value) throws Throwable {slot.payloadPublisher().assign(value);}

    @Test public void actualPropertiesHoldObjectAndArrayChildrenUntilFinalReleaseSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"class HOChild {function __destruct(){echo 'child;';}} class HOParent {public int $v=3;public ?HOChild $child=null;public array $items=[1];function __destruct(){echo 'parent:',$this->v,';';}}")) {
                var cp=f.provider("HOChild");var pp=f.provider("HOParent");
                try (var array=f.arrays.allocate(false)) {
                    array.append(scalar(f.domain,Memory.CONST_INT_1));
                    var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
                    for (var property : pp.properties()) defaults.put(property,property.getName().equals("items") ? array.binding() : scalar(f.domain,property.getDefaultValue(f.env).toImmutable()));
                    var child=f.objects.allocate(cp,f.env,f.defaults(cp));var parent=f.objects.allocate(pp,f.env,defaults);
                    var record=parent.certificate();var table=(PhpObjectProperties)parent.object().getProperties();
                    assertSame(record,((php.runtime.lang.support.IPhpOwnershipObject)parent.object()).getOwnershipObject());
                    assertSame(table.getStorageIdentity(),record.properties().backingIdentity());assertEquals(1,record.properties().node().incoming());
                    var items=record.properties().entry(table.getByScalar("items"));
                    try (var write=f.arrays.prepareWrite(items.ownerPublisher(),items.ownerPublisher().current())) {
                        assertTrue(write.separated());
                        var actualCarrier=items.ownerPublisher().carrier();
                        try (var publication=(PhpOwnershipArrays.RawPublication)write.preparePublication()) {
                            actualCarrier.publishOwnershipRaw(publication);publication.commit();
                        }
                        write.entry(Memory.CONST_INT_0).assign(scalar(f.domain,new LongMemory(2)));
                    }
                    assertEquals(1,array.array().getByScalar(Memory.CONST_INT_0).toLong());
                    assertEquals(2,table.getByScalar("items").toValue(ArrayMemory.class).getByScalar(Memory.CONST_INT_0).toLong());
                    assign(record.properties().entry(table.getByScalar("child")),child.binding());var childNode=child.binding().node();child.close();assertEquals(1,childNode.incoming());
                    parent.close();assertEquals(PhpOwnership.State.DISPOSED,childNode.state());assertEquals("parent:3;child;",f.text());
                    assertEquals(1,array.binding().node().incoming());
                }
            }
    }
    @Test public void typedCellSourceSurvivesUntilActualObjectDisposalSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"class HOTyped {public int $v=3;}")) {
                var provider=f.provider("HOTyped");var allocation=f.objects.allocate(provider,f.env,f.defaults(provider));
                var record=allocation.certificate();var carrier=record.properties().table().getByScalar("v");var slot=record.properties().entry(carrier);
                PhpOwnershipValues.Binding cell;
                try (var promotion=slot.preparePromotion(slot.ownerPublisher().current())) {cell=promotion.binding();promotion.store();promotion.commit();}
                try (var external=cell.host()) {
                    var canonical=(ReferenceMemory)cell.rawValue();assertTrue(canonical.hasPropertySources());
                    try {assign(slot,scalar(f.domain,new StringMemory("bad")));fail("typed source ignored");} catch (php.runtime.lang.exception.BaseTypeError expected) {}
                    assertEquals(3,canonical.toLong());allocation.close();assertFalse(canonical.hasPropertySources());
                    ((PhpOwnershipArrays.Entry)f.arrays.certificate(canonical).value()).assign(scalar(f.domain,new StringMemory("free")));assertEquals("free",canonical.toString());
                }
            }
    }
    @Test public void graphDisposalAfterActualThrowingDestructorStillReleasesSourcesAndChildrenSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"class HOThrow {public int $v=3;function __destruct(){echo $this->v;throw new Exception('owned-dtor');}}")) {
                var p=f.provider("HOThrow");var a=f.objects.allocate(p,f.env,f.defaults(p));var record=a.certificate();var node=record.node();var carrier=record.properties().table().getByScalar("v");
                assign(record.properties().entry(carrier),scalar(f.domain,new LongMemory(8)));int depth=f.env.getCallStack().getTop();
                try {a.close();fail("destructor failure lost");} catch (php.runtime.lang.BaseException expected) {assertEquals("owned-dtor",expected.getMessage(f.env).toString());}
                assertEquals(PhpOwnership.State.DISPOSED,node.state());assertFalse(carrier.hasPropertySources());assertEquals(depth,f.env.getCallStack().getTop());assertEquals("8",f.text());
                reject(()->PhpOwnershipValues.bindValue(f.domain,new ObjectMemory((IObject)node.identity()),node));
            }
    }
    @Test public void actualDestructorFlushAllowsReentryAndHostResurrectionBeforeSourceTeardownSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true}) {
            Runnable[] callback={null};var output=new ByteArrayOutputStream() {
                public synchronized void write(byte[] b,int offset,int length) {super.write(b,offset,length);if (callback[0] != null) {var next=callback[0];callback[0]=null;next.run();}}
            };
            try (var f=new Fixture(debug,dumped,"class HOKeep {public int $v=3;function __destruct(){echo 'keep;';flush();}}",output)) {
                var p=f.provider("HOKeep");var a=f.objects.allocate(p,f.env,f.defaults(p));var record=a.certificate();var node=record.node();var carrier=record.properties().table().getByScalar("v");
                PhpOwnershipDomain.OwnedValue[] saved={null};callback[0]=()->{
                    try {assertTrue(carrier.hasPropertySources());assertTrue(record.object().isFinalized());saved[0]=f.domain.hostValue(node,new ObjectMemory(record.object()));assertFalse(PhpObjectLifecycle.destruct(record.object(),f.env,PhpObjectLifecycle.Reason.OWNERSHIP_RELEASE));}
                    catch (Throwable failure) {throw new AssertionError(failure);}
                };
                a.close();assertNotNull(saved[0]);assertEquals(PhpOwnership.State.LIVE,node.state());assertTrue(carrier.hasPropertySources());saved[0].close();assertEquals(PhpOwnership.State.DISPOSED,node.state());assertFalse(carrier.hasPropertySources());assertEquals("keep;",f.text());
            }
        }
    }
    @Test public void certifiedForeignDefaultsAndOpaqueObjectsRejectBeforeProviderEffectsSourceAndDump() throws Throwable {
        for (boolean dumped : new boolean[]{false,true})
            try (var f=new Fixture(false,dumped,"class HOForeignParent {public $child=null;}")) {
                int[] validations={0};var entity=f.clazz("HOForeignParent");var p=f.objects.registerPropertiesProvider(entity,entity.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,v)->validations[0]++);
                var raw=new php.runtime.lang.BaseObject(f.env,entity) {};var opaque=PhpOwnershipValues.bindValue(f.domain,new ObjectMemory(raw),f.domain.object(raw,null));
                var defaults=f.defaults(p);defaults.put(p.properties().get(0),opaque);
                try {f.objects.allocate(p,f.env,defaults);fail("opaque object admitted");} catch (IllegalArgumentException expected) {}
                assertEquals(0,validations[0]);reject(()->f.objects.provider(new ClassEntity(new Context(""))));
                try (var foreign=new Fixture(false,dumped,"class HOForeignChild {}")) {
                    var fp=foreign.provider("HOForeignChild");try (var child=foreign.objects.allocate(fp,foreign.env,foreign.defaults(fp))) {
                        defaults.put(p.properties().get(0),child.binding());try {f.objects.allocate(p,f.env,defaults);fail("foreign object admitted");} catch (IllegalArgumentException expected) {}
                        assertEquals(0,validations[0]);var rawChild=child.object();
                        reject(()->PhpOwnershipValues.bindValue(f.domain,new ObjectMemory(rawChild),f.domain.object(rawChild,null)));
                    }
                }
                f.domain.collectCycles();
            }
    }
    @Test public void uninitializedTypedPromotionPreservesErrorAndNullableInitializationSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"class HOUninit {public int $v;public ?int $n;}")) {
                var p=f.provider("HOUninit");try (var a=f.objects.allocate(p,f.env,f.defaults(p))) {
                    var record=a.certificate();var table=record.properties().table();var v=table.getByScalar("v");var n=table.getByScalar("n");
                    assertTrue(v.getRawValue().isUndefined());assertTrue(n.getRawValue().isUndefined());int nodes=f.domain.graph().liveNodes();
                    try (var promotion=record.properties().entry(v).preparePromotion(record.properties().entry(v).ownerPublisher().current())) {fail("nonnullable reference initialization accepted");}
                    catch (php.runtime.lang.exception.BaseError expected) {}
                    assertTrue(v.getRawValue().isUndefined());assertEquals(nodes,f.domain.graph().liveNodes());
                    var slot=record.properties().entry(n);try (var promotion=slot.preparePromotion(slot.ownerPublisher().current())) {promotion.store();promotion.commit();}
                    assertTrue(n.toValue().isNull());assertTrue(n.hasPropertySources());
                }
            }
    }

    @Test public void physicalDuplicatesMutationSentinelAndCycleCollectionUseActualTableSourceAndDump() throws Throwable {
        for (boolean dumped : new boolean[]{false,true})
            try (var f=new Fixture(false,dumped,"class HOCycle {public $peer=null;public int $v=3;function __destruct(){echo 'cycle;';}}")) {
                var p=f.provider("HOCycle");var a=f.objects.allocate(p,f.env,f.defaults(p));var record=a.certificate();var table=record.properties().table();var node=record.node();
                record.properties().insertBucket("dup",scalar(f.domain,new LongMemory(1)));record.properties().insertBucket("dup",scalar(f.domain,new LongMemory(2)));assertEquals(4,table.size());
                reject(()->table.removeByScalar("v"));
                try {record.properties().insertBucket("v",scalar(f.domain,new StringMemory("bad")));fail("declared guard bypassed");} catch (IllegalArgumentException expected) {}reject(()->table.getByScalar("v").assign(new LongMemory(7)));reject(()->table.clear());
                var it=table.iterator();it.next();reject(it::remove);assertEquals(3,table.getByScalar("v").toLong());
                assign(record.properties().entry(table.getByScalar("peer")),a.binding());a.close();assertEquals(PhpOwnership.State.LIVE,node.state());
                assertEquals(2,f.domain.collectCycles());assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals("cycle;",f.text());
            }
    }
    /** The explicit host certificate does not authorize the ordinary PHP clone gateway. */
    @Test public void certifiedCloneRejectsBeforeCompiledAndNativeCallbacksSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"class HOClone {function __clone(){echo 'clone;';}} function ho_clone($a){return clone $a;}")) {
                var provider=f.provider("HOClone");
                try (var allocation=f.objects.allocate(provider,f.env,f.defaults(provider))) {
                    var actual=allocation.object();
                    try {f.env.fetchFunction("ho_clone").invoke(f.env,TraceInfo.UNKNOWN,new Memory[]{new ObjectMemory(actual)});fail("owned clone entered ordinary factory");}
                    catch (php.runtime.ext.java.JavaException expected) {assertTrue(expected.getThrowable() instanceof IllegalStateException);assertEquals("Certified object cloning requires an explicit owned allocation protocol",expected.getThrowable().getMessage());}
                    assertEquals("",f.text());assertSame(allocation.certificate(),((php.runtime.lang.BaseObject)actual).getOwnershipObject());
                    try {provider.entity().cloneObject(actual,f.env,TraceInfo.UNKNOWN);fail("direct factory accepted owned clone");}
                    catch (IllegalStateException expected) {}
                    assertEquals("",f.text());
                }
                var ordinary=f.clazz("HOClone").newObjectWithoutConstruct(f.env);
                var cloned=f.env.fetchFunction("ho_clone").invoke(f.env,TraceInfo.UNKNOWN,new Memory[]{new ObjectMemory(ordinary)}).toValue(ObjectMemory.class).value;
                assertNotSame(ordinary,cloned);assertEquals("clone;",f.text());
                var nativeEntity=new ClassEntity(new Context(""));nativeEntity.setName("HONativeClone");nativeEntity.setInternal(true);nativeEntity.setNativeClazz(NativeCloneShell.class);
                var nativeProvider=f.objects.registerPropertiesProvider(nativeEntity,NativeCloneShell.class.getConstructor(Environment.class,ClassEntity.class),(e,v)->{});
                NativeCloneShell.cloneCalls=0;
                try (var allocation=f.objects.allocate(nativeProvider,f.env,f.defaults(nativeProvider))) {
                    try {new ObjectMemory(allocation.object()).clone(f.env,TraceInfo.UNKNOWN);fail("native clone callback executed");}
                    catch (IllegalStateException expected) {}
                    assertEquals(0,NativeCloneShell.cloneCalls);
                }
                var nativeOrdinary=new NativeCloneShell(f.env,nativeEntity);
                var nativeCopy=new ObjectMemory(nativeOrdinary).clone(f.env,TraceInfo.UNKNOWN).toValue(ObjectMemory.class).value;
                assertNotSame(nativeOrdinary,nativeCopy);assertEquals(1,NativeCloneShell.cloneCalls);
            }
    }
    @Test public void ordinaryLegacyCloneRetainsItsFactoryAndNativeCallbackSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"class HOLegacyClone {public $v=3;function __clone(){echo 'clone;';}} function ho_legacy_clone($a){return clone $a;}",new ByteArrayOutputStream(),PhpLanguageProfile.JPHP_LEGACY)) {
                var original=f.clazz("HOLegacyClone").newObject(f.env,TraceInfo.UNKNOWN,false);
                var copy=f.env.fetchFunction("ho_legacy_clone").invoke(f.env,TraceInfo.UNKNOWN,new Memory[]{new ObjectMemory(original)}).toValue(ObjectMemory.class).value;
                assertNotSame(original,copy);assertEquals(3,copy.getProperties().getByScalar("v").toLong());assertEquals("clone;",f.text());
                var nativeEntity=new ClassEntity(new Context(""));nativeEntity.setName("HOLegacyNativeClone");nativeEntity.setInternal(true);nativeEntity.setNativeClazz(NativeCloneShell.class);
                NativeCloneShell.cloneCalls=0;var nativeOriginal=new NativeCloneShell(f.env,nativeEntity);
                assertNotSame(nativeOriginal,new ObjectMemory(nativeOriginal).clone(f.env,TraceInfo.UNKNOWN).toValue(ObjectMemory.class).value);assertEquals(1,NativeCloneShell.cloneCalls);
            }
    }
    public static final class NativeCloneShell extends php.runtime.lang.BaseObject implements php.runtime.lang.support.ICloneableObject<NativeCloneShell> {
        static int cloneCalls;
        public NativeCloneShell(Environment env,ClassEntity entity) {super(env,entity);}
        public NativeCloneShell __clone(Environment env,TraceInfo trace) {cloneCalls++;return new NativeCloneShell(env,getReflection());}
    }

}
