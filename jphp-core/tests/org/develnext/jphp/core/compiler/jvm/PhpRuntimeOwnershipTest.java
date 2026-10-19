package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real ordinary generated-body gates; no Session or automatic whole-runtime admission. */
public class PhpRuntimeOwnershipTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final Environment env;final ModuleEntity module;final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority;final PhpOwnershipDomain.Execution execution;
        Fixture(boolean debug,boolean dump,String source)throws Throwable {
            var scope=newScope();scope.setDebugMode(debug);env=new Environment(scope,new ByteArrayOutputStream());
            Context context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var compiled=new JvmCompiler(env,context).compile(false);
            if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(compiled,bytes);compiled=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=compiled;env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
            authority=new PhpRuntimeOwnership.Authority(env,domain);execution=authority.enter();
        }
        FunctionEntity function(String name){return env.fetchFunction(name);}
        void admit(String name)throws Throwable{var function=function(name);authority.registerFunction(function,function.getData(),(p,m,b)->assertTrue(p.unsupportedLowering().isEmpty()));}
        @Override public void close()throws Exception {authority.close();execution.close();try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }
    @Test public void diagnosticRetentionCannotBeConsumedAsProducerWhileNativeTemporaryCan()throws Throwable {
        try(var f=new Fixture(false,false,"return 0;");var frame=f.authority.frame();var parent=f.authority.arrays().allocate(true)){
            var entry=parent.insertBucket("value",PhpOwnershipValues.bindValue(f.domain,LongMemory.valueOf(0),null));
            var array=PhpOwnershipArrays.runtimeFresh(frame,4_000_040,new ArrayMemory());var node=array.getRuntimeOrigin().node();
            var facet=entry.runtimeFacet();var binding=PhpOwnershipValues.bindValue(f.domain,array,node);
            try(var diagnostic=frame.pinDiagnostic(array)){
                assertEquals(2,node.incoming());var before=facet.current();
                assertThrows(IllegalArgumentException.class,()->{try(var ignored=facet.prepareValue(before,binding,diagnostic.holder())){}});
                assertSame(before,facet.current());assertSame(array,diagnostic.holder().raw());assertEquals(2,node.incoming());
                var carrier=facet.carrier();
                try(var temporary=frame.pinTemporary(array,PhpRuntimeOwnership.Role.VALUE)){
                    assertEquals(3,node.incoming());
                    try(var publication=facet.prepareValue(before,binding,temporary.holder())){carrier.publishOwnershipRaw(publication);publication.commit();}
                    assertTrue(temporary.holder().raw().isUndefined());assertSame(array,facet.current().binding().rawValue());assertEquals(3,node.incoming());
                }
                assertEquals(3,node.incoming());
            }
            assertEquals(2,node.incoming());frame.releaseEval(4_000_040);assertEquals(1,node.incoming());
        }
    }
    @Test public void actualInvocationSendCannotBeConsumedAsNativeProducer()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})
            try(var f=new Fixture(debug,dump,"function ordinary_sender($a){return count([$a]);} return 0;");var parent=f.authority.arrays().allocate(true)){
                var entry=parent.insertBucket("value",PhpOwnershipValues.bindValue(f.domain,LongMemory.valueOf(0),null));var facet=entry.runtimeFacet();
                var method=php.runtime.ext.core.ArrayFunctions.class.getMethod("count",Environment.class,TraceInfo.class,Memory.class);
                int[] checks={0};
                f.authority.registerNative(method,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                    public void validateMember(java.lang.reflect.Method actual){assertEquals(method,actual);}
                    public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){
                        var frame=f.authority.currentFrame();var sender=frame.evalHolder(-1);assertNotNull(sender);assertTrue(sender.raw().isUndefined());
                        var before=facet.current();var binding=PhpOwnershipValues.bindValue(f.domain,sender.raw(),null);
                        assertThrows(IllegalArgumentException.class,()->{try(var ignored=facet.prepareValue(before,binding,sender)){}});
                        assertThrows(IllegalArgumentException.class,()->{try(var ignored=frame.prepareLocalFromEval(-1,4_999_000,"invalid",sender.raw(),PhpRuntimeOwnership.Role.VALUE)){}});
                        assertSame(before,facet.current());assertEquals(0,entry.carrier().toLong());checks[0]++;
                    }
                });
                var function=f.function("ordinary_sender");f.authority.registerFunction(function,function.getData(),(plan,member,bytes)->{});
                try(var result=f.authority.invoke(function,TraceInfo.UNKNOWN,LongMemory.valueOf(12))){assertEquals(1,result.raw().toLong());}
                assertEquals(1,checks[0]);
            }
    }
    @Test public void actualTableFacetReusesPublisherAndTransfersFreshEvalBeforeContainerRelease()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})
            try(var f=new Fixture(debug,dump,"class OrdinaryFacet {public int $v=3;public mixed $child=null;} return 0;");var frame=f.authority.frame()){
                var arrays=f.authority.arrays();var objects=f.authority.objects();var clazz=f.env.fetchClass("OrdinaryFacet");
                var provider=objects.registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,o)->{});
                var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
                for(var property:provider.properties())defaults.put(property,PhpOwnershipValues.bindValue(f.domain,property.getDefaultValue(f.env).toImmutable(),null));
                var parent=objects.allocate(provider,f.env,defaults);var properties=parent.certificate().properties();
                var physical=properties.entry(parent.object().getProperties().getByScalar("v"));
                var facet=physical.ownerPublisher().runtimeFacet();
                var holder=f.authority.adopt(facet);assertSame(holder,f.authority.adopt(facet));assertEquals(1,parent.binding().node().incoming());
                holder.assign(LongMemory.valueOf(9));assertEquals(9,parent.object().getProperties().getByScalar("v").toLong());
                assertThrows(Throwable.class,()->holder.assign(new StringMemory("invalid")));assertEquals(9,holder.raw().toLong());
                var cell=holder.reference();assertEquals(PhpRuntimeOwnership.Role.CELL,holder.role());assertSame(cell,holder.raw());
                var payload=f.authority.adopt(((PhpOwnershipArrays.Entry)arrays.certificate(cell).value()).runtimeFacet());
                assertThrows(IllegalStateException.class,payload::reference);assertThrows(IllegalStateException.class,()->payload.rebind(cell));payload.close();
                var child=objects.allocate(provider,f.env,defaults);var childNode=child.binding().node();
                Memory childRaw=frame.stageAllocation(4_000_030,child);assertEquals(1,childNode.incoming());
                var childPhysical=properties.entry(parent.object().getProperties().getByScalar("child"));
                var childHolder=f.authority.adopt(childPhysical.ownerPublisher().runtimeFacet());
                var binding=PhpOwnershipValues.bindValue(f.domain,childRaw,childNode);
                var childCarrier=childPhysical.ownerPublisher().carrier();
                try(var publication=childHolder.prepareFacetValue(childHolder.facetSnapshot(),binding,frame.evalHolder(4_000_030))){
                    childCarrier.publishOwnershipRaw(publication);publication.commit();
                }
                assertTrue(frame.evalHolder(4_000_030).raw().isUndefined());assertEquals(1,childNode.incoming());
                assertSame(((ObjectMemory)childRaw).value,((ObjectMemory)childHolder.raw()).value);
                childHolder.close();assertEquals(1,childNode.incoming());holder.close();assertEquals(1,parent.binding().node().incoming());
                parent.close();assertEquals(PhpOwnership.State.DISPOSED,childNode.state());
                assertThrows(IllegalStateException.class,()->f.authority.adopt(facet));
            }
    }
    @Test public void actualModuleBorrowsPersistentGlobalAddressUntilEnvironmentHolderRelease()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})
            try(var f=new Fixture(debug,dump,"$ordinary_global=17;$ordinary_global=null;return 7;")){
                f.authority.registerModule(f.module,f.module.getData(),(p,m,b)->assertEquals(PhpOwnershipExecution.EntryKind.MODULE,p.entryKind()));
                try(var result=f.authority.include(f.module)){assertEquals(7,result.raw().toLong());}
                ReferenceMemory actual=f.env.getGlobals().getByScalar("ordinary_global");
                assertNotNull(actual.getRuntimeHolder());assertNull(f.authority.currentFrame());assertTrue(actual.getRawValue().isNull());
                assertSame(actual,f.authority.globalAddress("ordinary_global"));
            }
    }
    @Test public void actualOrdinaryCvImportsReplaceAndReturnAcrossSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})
            try(var f=new Fixture(debug,dump,"function ordinary_cv($a){$x=$a;$a=null;return $x;} return 0;")){
                f.admit("ordinary_cv");assertNull(PhpOwnershipExecution.current(f.env));
                try(var result=f.authority.invoke(f.function("ordinary_cv"),TraceInfo.UNKNOWN,LongMemory.valueOf(17))){assertEquals(17,result.raw().toLong());}
                assertNull(f.authority.currentFrame());assertEquals(0,f.domain.graph().liveNodes());
            }
    }
    @Test public void actualReturnRootKeepsTypedSourceUntilCallerReleasesObject()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})
            try(var f=new Fixture(debug,dump,"class OrdinaryTyped {public int $v=3;} function ordinary_keep($a){$x=$a;$a=null;return $x;} return 0;")){
                f.admit("ordinary_keep");var arrays=new PhpOwnershipArrays(f.domain.heap());var objects=new PhpOwnershipObjects(f.domain.heap(),arrays);
                var clazz=f.env.fetchClass("OrdinaryTyped");var provider=objects.registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,o)->{});
                var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
                for(var property:provider.properties())defaults.put(property,PhpOwnershipValues.bindValue(f.domain,property.getDefaultValue(f.env).toImmutable(),null));
                var allocation=objects.allocate(provider,f.env,defaults);var node=allocation.binding().node();var actual=allocation.object();
                var property=allocation.certificate().properties().entry(actual.getProperties().getByScalar("v"));PhpOwnershipValues.Binding binding;
                try(var promotion=property.preparePromotion(property.ownerPublisher().current())){binding=promotion.binding();promotion.store();promotion.commit();}
                try(var alias=binding.host()){
                    var canonical=(ReferenceMemory)binding.rawValue();
                    var result=f.authority.invoke(f.function("ordinary_keep"),TraceInfo.UNKNOWN,new ObjectMemory(actual));allocation.close();
                    assertSame(actual,((ObjectMemory)result.raw()).value);assertEquals(1,node.incoming());assertTrue(canonical.hasPropertySources());
                    result.close();assertEquals(PhpOwnership.State.DISPOSED,node.state());assertFalse(canonical.hasPropertySources());
                    ((PhpOwnershipArrays.Entry)arrays.certificate(canonical).value()).assign(PhpOwnershipValues.bindValue(f.domain,new StringMemory("free"),null));
                    assertEquals("free",canonical.toString());
                }
            }
    }
    @Test public void actualModuleGlobalReleaseDetachesOnlyFinalObjectSourceBeforeNextPhpStatement()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})
            try(var f=new Fixture(debug,dump,"class OrdinaryGlobalTyped {public int $v=3;} $ordinary_global=null;return 7;")){
                f.authority.registerModule(f.module,f.module.getData(),(p,m,b)->{});
                var arrays=new PhpOwnershipArrays(f.domain.heap());var objects=new PhpOwnershipObjects(f.domain.heap(),arrays);
                var clazz=f.env.fetchClass("OrdinaryGlobalTyped");var provider=objects.registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,o)->{});
                var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
                for(var property:provider.properties())defaults.put(property,PhpOwnershipValues.bindValue(f.domain,property.getDefaultValue(f.env).toImmutable(),null));
                var allocation=objects.allocate(provider,f.env,defaults);var node=allocation.binding().node();var actual=allocation.object();
                var property=allocation.certificate().properties().entry(actual.getProperties().getByScalar("v"));PhpOwnershipValues.Binding binding;
                try(var promotion=property.preparePromotion(property.ownerPublisher().current())){binding=promotion.binding();promotion.store();promotion.commit();}
                try(var alias=binding.host()){
                    var canonical=(ReferenceMemory)binding.rawValue();f.authority.assignGlobal("ordinary_global",new ObjectMemory(actual));allocation.close();assertEquals(1,node.incoming());
                    try(var result=f.authority.include(f.module)){assertEquals(7,result.raw().toLong());}
                    assertEquals(PhpOwnership.State.DISPOSED,node.state());assertFalse(canonical.hasPropertySources());
                    ((PhpOwnershipArrays.Entry)arrays.certificate(canonical).value()).assign(PhpOwnershipValues.bindValue(f.domain,new StringMemory("free"),null));
                    assertEquals("free",canonical.toString());
                }
            }
    }
    @Test public void actualAddressPromotionHasOneCellPayloadAndUnsetPreservesOtherAlias()throws Throwable {
        try(var f=new Fixture(false,false,"return 0;");var frame=f.authority.frame()){
            var address=new ReferenceMemory();
            try(var publication=frame.prepareLocal(1,"a",address,PhpRuntimeOwnership.Role.BORROW_ADDRESS)){publication.afterLocalStore();publication.commit();}
            address.assign(8);var cell=address.getReferenceForBinding();assertEquals(8,cell.toLong());assertEquals(1,cell.getRuntimeCell().node().incoming());
            var second=new ReferenceMemory();
            try(var publication=frame.prepareLocal(2,"b",second,PhpRuntimeOwnership.Role.BORROW_ADDRESS)){publication.afterLocalStore();publication.commit();}
            second.assignRef(cell);assertEquals(2,cell.getRuntimeCell().node().incoming());address.unset();second.assign(9);assertEquals(9,cell.toLong());
            assertTrue(address.getRawValue().isUndefined());assertEquals(1,cell.getRuntimeCell().node().incoming());
        }
    }
    @Test public void realNativePacketTransfersArrayEvalToSendAndStagesResultBeforeRetirement()throws Throwable {
        try(var f=new Fixture(false,false,"return 0;");var frame=f.authority.frame()) {
            var method=php.runtime.ext.core.ArrayFunctions.class.getMethod("count",Environment.class,TraceInfo.class,Memory.class);
            f.authority.registerNative(method,new PhpRuntimeOwnership.CompleteNativeProtocol() {
                public void validateMember(java.lang.reflect.Method actual){assertEquals(method,actual);}
                public void validateArguments(List<PhpRuntimeOwnership.Value> actual){assertEquals(1,actual.size());assertEquals(PhpRuntimeOwnership.Role.VALUE,actual.get(0).role());}
            });
            var array=PhpOwnershipArrays.runtimeFresh(frame,4_000_010,new ArrayMemory());var node=array.getRuntimeOrigin().node();
            var packet=PhpRuntimeOwnership.nativeCall(frame,4_000_011,method.getDeclaringClass(),method.getName(),PhpOwnershipExecution.descriptor(method),1);
            assertSame(array,PhpRuntimeOwnership.nativeSend(packet,0,array,4_000_010));assertEquals(1,node.incoming());
            var body=PhpRuntimeOwnership.nativeEnterBody(packet);
            assertSame(body,PhpRuntimeOwnership.currentNativeBody(f.env));
            Memory result=php.runtime.ext.core.ArrayFunctions.count(f.env,TraceInfo.UNKNOWN,packet.argument(0));
            PhpRuntimeOwnership.nativeLeaveBody(body);assertNull(PhpRuntimeOwnership.currentNativeBody(f.env));
            assertSame(result,PhpRuntimeOwnership.nativeResult(packet,result,4_000_011));assertEquals(PhpOwnership.State.DISPOSED,node.state());
            assertEquals(0,frame.evalHolder(4_000_011).raw().toLong());assertThrows(IllegalStateException.class,()->packet.argument(0));
        }
    }
    @Test public void nativePacketRetainsActualPostBorrowCellWithoutPromotingPlainValues()throws Throwable {
        try(var f=new Fixture(false,false,"return 0;");var frame=f.authority.frame()) {
            var method=php.runtime.ext.core.ArrayFunctions.class.getMethod("count",Environment.class,TraceInfo.class,Memory.class);
            f.authority.registerNative(method,new PhpRuntimeOwnership.CompleteNativeProtocol() {
                public void validateMember(java.lang.reflect.Method actual){assertEquals(method,actual);}
                public void validateArguments(List<PhpRuntimeOwnership.Value> actual){assertEquals(PhpRuntimeOwnership.Role.CELL,actual.get(0).role());}
            });
            var address=new ReferenceMemory();try(var p=frame.prepareLocal(1,"scalar",address,PhpRuntimeOwnership.Role.BORROW_ADDRESS)){p.afterLocalStore();p.commit();}
            address.assign(8);var cell=address.getReferenceForBinding();
            try(var packet=frame.nativeCall(4_000_011,method.getDeclaringClass(),method.getName(),PhpOwnershipExecution.descriptor(method),1)) {
                packet.send(0,cell,-1);assertSame(cell,packet.argument(0));assertEquals(2,cell.getRuntimeCell().node().incoming());
                address.assign(9);assertEquals(9,packet.argument(0).toLong());
            }
            assertEquals(1,cell.getRuntimeCell().node().incoming());
        }
    }
    @Test public void unsupportedOrdinaryBodyRejectsBeforeEffectsAndOldUnplannedEntryIsNotAdopted()throws Throwable {
        try(var f=new Fixture(false,false,"function ordinary_unknown(){echo 'effect';return new stdClass;} return 0;")){
            var function=f.function("ordinary_unknown");
            assertThrows(IllegalArgumentException.class,()->f.authority.registerFunction(function,function.getData(),(p,m,b)->fail("Incomplete body reached host contract")));
            assertThrows(IllegalArgumentException.class,()->f.authority.invoke(function,TraceInfo.UNKNOWN));
            assertNull(f.authority.currentFrame());
        }
    }
}
