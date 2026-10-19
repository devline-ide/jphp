package org.develnext.jphp.core.compiler.jvm.statement;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.PhpTypedSourceLifetime;
import php.runtime.PhpTypedSourcePlan;
import php.runtime.reflection.ClassEntity;
import static org.objectweb.asm.Opcodes.*;

/** Only a closed physical allocation/CV/reference/unset interval receives sparse observer calls. */
public final class TypedSourceFrameCompiler {
    private final MethodStmtCompiler method;
    private record Allocation(ClassEntity declaration,AbstractInsnNode result) {}
    private final List<Allocation> allocations=new ArrayList<>();
    private final Set<MethodInsnNode> observers=Collections.newSetFromMap(new IdentityHashMap<>());
    public boolean isObserver(MethodInsnNode call){return observers.contains(call) && call.getOpcode()==INVOKESTATIC && call.owner.equals(PhpTypedSourcePlan.RUNTIME);}
    public TypedSourceFrameCompiler(MethodStmtCompiler method){this.method=method;}
    public void allocation(ClassEntity declaration,AbstractInsnNode result) {
        if(method.getCompiler().getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5
                && PhpTypedSourceLifetime.eligible(declaration))allocations.add(new Allocation(declaration,result));
    }
    private final Map<Integer,List<AbstractInsnNode>> inserted=new HashMap<>();
    private boolean prepared,finished;
    /** Register exact observers before ownership and CELL producers serialize or rewrite calls. */
    public void prepare() {
        if(prepared)return;prepared=true;
        if(allocations.isEmpty())return;
        int id=0;
        for(var allocation:allocations) {
            MethodInsnNode assignment=null,unset=null;int local=-1;
            for(AbstractInsnNode node=allocation.result().getNext();node!=null;node=node.getNext()) {
                if(node instanceof MethodInsnNode call && call.getOpcode()==INVOKESTATIC
                        &&call.owner.equals("php/runtime/Memory")&&call.name.equals("assignRight")
                        &&call.desc.equals("(Lphp/runtime/Memory;Lphp/runtime/Memory;)Lphp/runtime/Memory;")) {
                    if(previous(call) instanceof VarInsnNode cv && cv.getOpcode()==ALOAD && physicalCv(cv.var)) {assignment=call;local=cv.var;}
                    break;
                }
                if(isAllocationResult(node))break;
            }
            if(assignment==null)continue;
            for(AbstractInsnNode node=assignment.getNext();node!=null;node=node.getNext()) {
                if(node instanceof MethodInsnNode call && call.getOpcode()==INVOKESTATIC
                        &&call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("unsetLocal")) {
                    AbstractInsnNode cv=previous(previous(call));
                    if(cv instanceof VarInsnNode read&&read.getOpcode()==ALOAD&&read.var==local){unset=call;break;}
                }
                if(isAllocationResult(node))break;
            }
            if(unset==null)continue;
            List<AbstractInsnNode> nodes=new ArrayList<>();
            observe(allocation.result(),"created",allocation.declaration(),id,local,true,nodes);
            observe(assignment,"published",allocation.declaration(),id,local,false,nodes);
            observe(unset,"released",allocation.declaration(),id,local,false,nodes);
            inserted.put(id++,nodes);
            for(var node:nodes)if(node instanceof MethodInsnNode call)observers.add(call);
        }
    }
    /** Admit final default/CELL bytecode before final transport instruction ordinals are sealed. */
    public void finish() {
        if(finished)return;finished=true;prepare();
        if(inserted.isEmpty())return;
        // The runtime reader is also the compiler's preflight; no second weaker admission algorithm.
        ClassWriter draft=new ClassWriter(0);draft.visit(V17,ACC_PUBLIC,method.clazz.node.name,null,"java/lang/Object",null);
        int oldStack=method.node.maxStack,oldLocals=method.node.maxLocals;
        method.node.maxStack=65535;method.node.maxLocals=65535;
        try {method.node.accept(draft.visitMethod(method.node.access,method.node.name,method.node.desc,method.node.signature,null));}
        finally {method.node.maxStack=oldStack;method.node.maxLocals=oldLocals;}
        draft.visitEnd();Set<Integer> admitted=new HashSet<>();
        try {
            var model=java.lang.classfile.ClassFile.of().parse(draft.toByteArray());
            for(var body:model.methods())admitted.addAll(PhpTypedSourcePlan.inspect(body).keySet());
        }catch(RuntimeException invalid){admitted.clear();}
        for(var entry:inserted.entrySet())if(!admitted.contains(entry.getKey()))for(var node:entry.getValue()){method.node.instructions.remove(node);if(node instanceof MethodInsnNode call)observers.remove(call);}
        if(admitted.isEmpty())return;
        for(var entry:inserted.entrySet())if(admitted.contains(entry.getKey()))for(var node:entry.getValue())if(node instanceof MethodInsnNode call)observers.add(call);
        // Abandon pending proof on every existing context cleanup, including exception handlers.
        for(AbstractInsnNode node:method.node.instructions.toArray())if(node instanceof MethodInsnNode call
                &&call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/PhpPropertyWriteContext")&&call.name.equals("leave")) {
            InsnList cleanup=new InsnList();cleanup.add(new InsnNode(DUP));
            MethodInsnNode leave=new MethodInsnNode(INVOKESTATIC,PhpTypedSourcePlan.RUNTIME,"leave","(Lphp/runtime/PhpPropertyWriteContext$Frame;)V",false);cleanup.add(leave);observers.add(leave);
            method.node.instructions.insertBefore(call,cleanup);
        }
    }
    private boolean isAllocationResult(AbstractInsnNode node){return allocations.stream().anyMatch(next->next.result()==node);}
    private boolean physicalCv(int local){return method.getLocalVariables().values().stream().anyMatch(cv->cv.index==local&&cv.isOwnershipLocal()&&cv.isReference());}
    private void observe(AbstractInsnNode after,String helper,ClassEntity declaration,int id,int local,boolean duplicate,List<AbstractInsnNode> inserted) {
        InsnList code=new InsnList();code.add(duplicate?new InsnNode(DUP):new VarInsnNode(ALOAD,local));
        code.add(new LdcInsnNode(Type.getObjectType(declaration.getCompiledInternalName())));
        code.add(new LdcInsnNode(Type.getObjectType(method.clazz.node.name)));code.add(new LdcInsnNode(method.node.name));code.add(new LdcInsnNode(method.node.desc));
        code.add(new LdcInsnNode(id));code.add(new LdcInsnNode(local));
        code.add(new MethodInsnNode(INVOKESTATIC,PhpTypedSourcePlan.RUNTIME,helper,PhpTypedSourcePlan.OBSERVER,false));
        inserted.addAll(Arrays.asList(code.toArray()));method.node.instructions.insert(after,code);
    }
    private static AbstractInsnNode previous(AbstractInsnNode node){if(node==null)return null;do{node=node.getPrevious();}while(node!=null&&node.getOpcode()<0);return node;}
}
