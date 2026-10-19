package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.env.*;
import php.runtime.memory.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.junit.Assert.*;

/** Native publisher boundaries on actual Memory allocations; compiler BODY execution is tested separately. */
public class PhpRuntimeNativeArenaTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final Environment env=new Environment(newScope(),new ByteArrayOutputStream());
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority=new PhpRuntimeOwnership.Authority(env,domain);
        final PhpOwnershipDomain.Execution execution=authority.enter();
        final PhpRuntimeOwnership.Frame frame=authority.frame();
        final java.lang.reflect.Method member=php.runtime.ext.core.ArrayFunctions.class.getMethod("count",Environment.class,TraceInfo.class,Memory.class);
        Fixture()throws Throwable {
            authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                public void validateArguments(List<PhpRuntimeOwnership.Value> args){assertEquals(1,args.size());}
            });
        }
        PhpRuntimeOwnership.NativePacket packet(int id)throws Throwable {
            var packet=frame.nativeCall(id,member.getDeclaringClass(),member.getName(),PhpOwnershipExecution.descriptor(member),1);
            packet.send(0,Memory.NULL,-1);return packet;
        }
        @Override public void close()throws Exception {frame.close();authority.close();execution.close();try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }
    @Test public void nativeBodyByteProofRejectsChangedHelperGuardAndHistoricalTransport()throws Throwable {
        for(boolean debug:new boolean[]{false,true}){var env=new Environment(newScope(),new ByteArrayOutputStream());try {
            env.scope.setDebugMode(debug);Context context=new Context("function body_proof($n){return count([$n]);} return 0;");context.setPhpLanguageProfile(php.runtime.common.PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
            var function=env.fetchFunction("body_proof");String member=function.getNativeMethod().getName(),descriptor=PhpOwnershipExecution.descriptor(function.getNativeMethod());byte[] original=function.getData();
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,member,descriptor);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
            for(int mutation=0;mutation<3;mutation++) {
                var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(original).accept(node,new org.objectweb.asm.Attribute[]{new org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.RuntimeFrameAttribute(new byte[0])},0);
                var target=node.methods.stream().filter(m->m.name.equals(member)&&m.desc.equals(descriptor)).findFirst().orElseThrow();
                if(mutation==0) {
                    var helper=java.util.Arrays.stream(target.instructions.toArray()).filter(i->i instanceof org.objectweb.asm.tree.MethodInsnNode&&((org.objectweb.asm.tree.MethodInsnNode)i).name.equals("nativeLeaveBody")).findFirst().orElseThrow();
                    ((org.objectweb.asm.tree.MethodInsnNode)helper).name="unknownLeaveBody";
                } else if(mutation==1) {
                    var guard=target.tryCatchBlocks.stream().filter(g->"java/lang/Throwable".equals(g.type)).findFirst().orElseThrow();guard.type="java/lang/Exception";
                } else {
                    var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);out.writeInt(2);out.writeUTF(plan.owner());out.writeUTF(plan.method());out.writeUTF(plan.descriptor());out.writeInt(plan.producers().size());
                    for(var producer:plan.producers()){out.writeInt(producer.id());out.writeUTF(producer.kind());out.writeInt(producer.consumers().size());for(String consumer:producer.consumers())out.writeUTF(consumer);}
                    out.writeInt(0);out.writeInt(plan.nativeMembers().size());for(var dependency:plan.nativeMembers()){out.writeInt(dependency.id());out.writeUTF(dependency.owner());out.writeUTF(dependency.name());out.writeUTF(dependency.descriptor());out.writeInt(dependency.argc());}
                    target.attrs.removeIf(attr->attr.type.equals("JPHP_Runtime_Frame_v1"));target.attrs.add(new org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.RuntimeFrameAttribute(bytes.toByteArray()));
                }
                var writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer);byte[] changed=writer.toByteArray();
                if(mutation<2)assertThrows(java.io.IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,member,descriptor));
                else assertTrue(PhpRuntimeOwnership.readRuntimePlan(changed,member,descriptor).unsupported().stream().anyMatch(debt->debt.startsWith("native-body-protocol-missing:")));
            }
        }finally{env.doFinal();}}
    }
    @Test public void rootPromotionAndValueResultUseOneActualTemporaryAddress()throws Throwable {
        try(var f=new Fixture();var packet=f.packet(4_000_501)){
            assertNull(PhpRuntimeOwnership.currentNativeBody(f.env));
            var body=PhpRuntimeOwnership.nativeEnterBody(packet);var arena=body.arena();var root=arena.allocateArray();
            var storage=root.binding().node();assertEquals(1,storage.incoming());assertTrue(root.assembly().matchesArray((ArrayMemory)root.raw()));
            var cell=root.promote();var cellNode=root.binding().node();assertEquals(PhpRuntimeOwnership.Role.CELL,root.holder().role());
            assertEquals(1,storage.incoming());assertEquals(1,cellNode.incoming());assertSame(cell,root.promote());
            Memory result=root.publishResult();assertTrue(result instanceof ArrayMemory);assertSame(storage,((ArrayMemory)result).getOwnershipStorage().node());
            PhpRuntimeOwnership.nativeLeaveBody(body);assertNull(PhpRuntimeOwnership.currentNativeBody(f.env));
            assertThrows(IllegalStateException.class,()->arena.temp(LongMemory.valueOf(1)));
            packet.resultToEval(result,4_000_501);assertEquals(PhpOwnership.State.DISPOSED,cellNode.state());assertEquals(1,storage.incoming());
            assertSame(storage,f.frame.evalHolder(4_000_501).node());f.frame.releaseEval(4_000_501);assertEquals(PhpOwnership.State.DISPOSED,storage.state());
        }
    }
    @Test public void nativePlacementMovesProducerAndClearUsesGenuinelyEmptyCleanup()throws Throwable {
        try(var f=new Fixture();var packet=f.packet(4_000_502)) {
            var body=PhpRuntimeOwnership.nativeEnterBody(packet);var arena=body.arena();var root=arena.allocateArray();
            var child=arena.allocateArray();var childNode=child.binding().node();var address=root.assembly().address("child");
            try(var publication=address.prepareMoveFrom(address.current(),child,child.current())){publication.store();publication.commit();}
            assertNull(child.binding());assertEquals(1,childNode.incoming());assertSame(childNode,address.resolve().ownerPublisher().current().binding().node());
            var cleanup=arena.empty();assertNull(cleanup.current().binding());
            try(var publication=address.prepareClearTo(address.current(),cleanup,cleanup.current())){publication.store();publication.commit();}
            assertTrue(address.resolve().ownerPublisher().current().binding().rawValue().isNull());assertSame(childNode,cleanup.binding().node());assertEquals(1,childNode.incoming());
            cleanup.close();assertEquals(PhpOwnership.State.DISPOSED,childNode.state());
            Memory result=root.publishResult();PhpRuntimeOwnership.nativeLeaveBody(body);packet.resultToEval(result,4_000_502);
        }
    }
    @Test public void failureCleanupBalancesNestedBodyAndPreservesPrimaryIdentity()throws Throwable {
        try(var f=new Fixture();var outer=f.packet(4_000_503)) {
            var outerBody=PhpRuntimeOwnership.nativeEnterBody(outer);var root=outerBody.arena().allocateArray();var node=root.binding().node();
            try(var inner=f.packet(4_000_504)){
                var innerBody=PhpRuntimeOwnership.nativeEnterBody(inner);var innerArray=innerBody.arena().allocateArray();var innerNode=innerArray.binding().node();
                assertThrows(IllegalStateException.class,()->outerBody.arena().empty());
                Throwable primary=new IllegalArgumentException("actual native body failure");
                assertSame(primary,PhpRuntimeOwnership.nativeFailBody(innerBody,primary));assertEquals(PhpOwnership.State.DISPOSED,innerNode.state());
                assertSame(outerBody,PhpRuntimeOwnership.currentNativeBody(f.env));assertEquals(PhpOwnership.State.LIVE,node.state());
            }
            Memory result=root.publishResult();PhpRuntimeOwnership.nativeLeaveBody(outerBody);outer.resultToEval(result,4_000_503);
            assertThrows(IllegalStateException.class,()->root.raw());assertNull(PhpRuntimeOwnership.currentNativeBody(f.env));
        }
    }
}
