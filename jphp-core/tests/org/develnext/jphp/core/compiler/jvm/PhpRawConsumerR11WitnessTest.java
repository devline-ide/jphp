package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.PhpOwnershipExecution;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import static org.junit.Assert.*;

/** Cold mutations retain the original callback fixture and its complete generated object allocation. */
public class PhpRawConsumerR11WitnessTest {
    private interface Mutation { void alter(MethodNode method, List<AbstractInsnNode> ops,
            PhpRuntimeOwnership.ObjectNew allocation, PhpRuntimeOwnership.LiteralEntry entry); }
    private static LabelNode target(MethodNode method, AbstractInsnNode instruction) {
        LabelNode label=new LabelNode();method.instructions.insertBefore(instruction,label);return label;
    }
    private static void modes(Mutation mutation)throws Throwable {
        Class<?> fixture=Class.forName("org.develnext.jphp.core.compiler.jvm.PhpOwnedNativeDirectCvTest$Fixture");
        var ctor=fixture.getDeclaredConstructor(boolean.class,boolean.class,boolean.class);ctor.setAccessible(true);
        var envField=fixture.getDeclaredField("env");envField.setAccessible(true);
        var close=fixture.getDeclaredMethod("close");close.setAccessible(true);
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            Object f;
            try {f=ctor.newInstance(debug,dumped,false);}catch(InvocationTargetException failure){throw failure.getCause();}
            try {
                var env=(php.runtime.env.Environment)envField.get(f);var function=env.fetchFunction("owned_element");
                var member=function.getNativeMethod();byte[] original=function.getData();
                assertTrue(((php.runtime.loader.RuntimeClassLoader)member.getDeclaringClass().getClassLoader()).matchesDefinedBytecode(member.getDeclaringClass(),original));
                String descriptor=PhpOwnershipExecution.descriptor(member);
                var plan=PhpRuntimeOwnership.readRuntimePlan(original,member.getName(),descriptor);assertNotNull(plan);
                var allocation=plan.objectNews().stream().filter(value->plan.literalEntries().stream().anyMatch(entry->entry.wrapper()==value.end())).findFirst().orElseThrow();
                var entry=plan.literalEntries().stream().filter(value->value.wrapper()==allocation.end()).findFirst().orElseThrow();
                assertTrue(plan.producers().stream().anyMatch(value->value.id()==allocation.id()&&value.consumers().contains("ENTRY")));
                ClassNode tree=new ClassNode();new org.objectweb.asm.ClassReader(original).accept(tree,0);
                MethodNode method=tree.methods.stream().filter(value->value.name.equals(member.getName())&&value.desc.equals(descriptor)).findFirst().orElseThrow();
                List<AbstractInsnNode> ops=Arrays.stream(method.instructions.toArray()).filter(value->value.getOpcode()>=0).toList();
                assertEquals(Opcodes.GOTO,ops.get(allocation.helper()+1).getOpcode());
                mutation.alter(method,ops,allocation,entry);
                var writer=new org.objectweb.asm.ClassWriter(0);tree.accept(writer);
                boolean rejected=false;
                try {PhpRuntimeOwnership.readRuntimePlan(writer.toByteArray(),member.getName(),descriptor);}catch(IOException expected){rejected=true;}
                assertTrue("Forged object/wrapper ingress was accepted: debug="+debug+", PHB="+dumped,rejected);
                assertNotNull(PhpRuntimeOwnership.readRuntimePlan(original,member.getName(),descriptor));
            } finally {try {close.invoke(f);}catch(InvocationTargetException failure){throw failure.getCause();}}
        }
    }
    @Test public void foreignBranchCannotEnterAnOtherwiseValidObjectWrapper()throws Throwable {
        modes((method,ops,allocation,entry)->{
            // Retarget the subsequent child-release guard: it is not the admitted allocation's passive join.
            JumpInsnNode foreign=(JumpInsnNode)ops.subList(entry.helper()+1,ops.size()).stream()
                    .filter(value->value.getOpcode()==Opcodes.IFNULL).findFirst().orElseThrow();
            foreign.label=target(method,ops.get(entry.wrapper()));
        });
    }
    @Test public void allocationJoinCannotSkipTheActualKeyValueWrapper()throws Throwable {
        modes((method,ops,allocation,entry)->{
            JumpInsnNode actual=(JumpInsnNode)ops.get(allocation.helper()+1);
            actual.label=target(method,ops.get(entry.wrapper()+1));
        });
    }
}
