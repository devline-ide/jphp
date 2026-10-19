package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.Constants;
import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.value.ClosureStmtToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.macro.ClassMacroToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ArgumentStmtToken;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import php.runtime.PhpOwnershipClosures;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.lang.IObject;
import php.runtime.lang.Closure;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.helper.ClosureEntity;

import java.util.Collection;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.AASTORE;

public class ClosureValueCompiler extends BaseExprCompiler<ClosureStmtToken> {
    public ClosureValueCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    protected void writePushUses(Collection<ArgumentStmtToken> parameters){writePushUses(parameters,null);}
    protected void writePushUses(Collection<ArgumentStmtToken> parameters,ClosureEntity closure){
        if (parameters.isEmpty()){
            add(new InsnNode(ACONST_NULL));
            expr.stackPush(Memory.Type.REFERENCE);
            return;
        }

        expr.writePushSmallInt(parameters.size());
        add(new TypeInsnNode(ANEWARRAY, Type.getInternalName(Memory.class)));
        expr.stackPop();
        expr.stackPush(Memory.Type.REFERENCE);

        int i = 0;
        for(ArgumentStmtToken param : parameters){
            expr.writePushDup();
            expr.writePushSmallInt(i);

            LocalVariable local = method.getLocalVariable(param.getName().getName());
            if (local == null)
                expr.writePushNull();
            else
                expr.writeVarLoad(local);

            if (!param.isReference())
                expr.writePopBoxing(true);
            else if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                expr.writePopBoxing(false);
                expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "exportReference", Memory.class, Memory.class);
            }

            InsnNode store=new InsnNode(AASTORE);add(store);
            if(closure!=null&&local!=null&&compiler.getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5)
                method.primitiveCells().captureStore(store,i,param.isReference(),local.index,closure.getInternalName());
            expr.stackPop();
            expr.stackPop();
            expr.stackPop();
            i++;
        }
    }

    protected void writeContext() {
        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && (method.clazz.getEntity().isTrait()
                || method.clazz.getClassContext() != null && method.clazz.getClassContext().isTrait())) {
            expr.writePushEnv();
            expr.writePushTraceInfo(method.clazz.statement);
            expr.writeSysDynamicCall(Environment.class, "__getSelf", String.class, php.runtime.env.TraceInfo.class);
        } else if (method.clazz.getClassContext() != null && method.clazz.getClassContext().isTrait()) {
            expr.writePushEnv();
            expr.writeSysDynamicCall(Environment.class, "__getMacroClass", Memory.class);
            expr.writePopString();
        } else {
            if (method.clazz.getClassContext() != null) {
                expr.writePushConstString(method.clazz.getClassContext().getFulledName());
            } else {
                if (method.clazz.isSystem()) {
                    expr.writePushConstNull();
                } else {
                    expr.writePushConstString(method.clazz.getEntity().getName());
                }
            }
        }
    }

    /** Do not define a cached CV only in the connected branch and reuse it in its passive sibling. */
    private void writeRuntimeThis() {
        if (method.clazz.isClosure() || method.getGeneratorEntity() != null) {
            expr.writePushThis();
        } else if (methodStatement.isStatic()) {
            expr.writePushNull();
        } else {
            LabelNode receiver=new LabelNode(),end=new LabelNode();
            expr.writeVarLoad("~this");
            expr.writeSysDynamicCall(IObject.class,"isMock",Boolean.TYPE);
            add(new JumpInsnNode(IFEQ,receiver));expr.stackPop();
            expr.writePushMemory(Memory.UNDEFINED);expr.stackPop();add(new JumpInsnNode(GOTO,end));
            add(receiver);expr.writeVarLoad("~this");
            expr.writeSysStaticCall(ObjectMemory.class,"valueOf",Memory.class,IObject.class);
            add(end);
        }
    }

    /** The physical empty shell owns its EVAL before any capture read can run a notice handler. */
    private LabelNode writeRuntimeProducer(ClosureStmtToken closure,ClosureEntity entity,boolean thisExists,int evalId) {
        LabelNode passive=new LabelNode(),end=new LabelNode();
        method.ownership().runtimeFallback(passive,end);
        add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));add(new JumpInsnNode(IFNULL,passive));
        LocalVariable allocation=method.addLocalVariable("~runtimeClosure"+evalId,null,Object.class);
        add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));expr.stackPush(Memory.Type.REFERENCE);
        expr.writePushConstInt(evalId);expr.writePushEnv();
        expr.writePushConstString(compiler.getModule().getInternalName());expr.writePushConstInt((int)entity.getId());
        expr.writeSysDynamicCall(Environment.class,"__getClosure",ClassEntity.class,String.class,Integer.TYPE);
        MethodInsnNode lookup=(MethodInsnNode)node.instructions.getLast();
        add(new TypeInsnNode(CHECKCAST,Type.getInternalName(ClosureEntity.class)));
        expr.writeSysStaticCall(PhpOwnershipClosures.class,"prepareRuntime",PhpOwnershipClosures.RuntimeAllocation.class,
                php.runtime.PhpRuntimeOwnership.Frame.class,Integer.TYPE,ClosureEntity.class);
        MethodInsnNode prepare=(MethodInsnNode)node.instructions.getLast();
        method.ownership().runtimeClosureLiteral(evalId,entity.getInternalName(),compiler.getModule().getInternalName(),(int)entity.getId(),lookup,prepare);
        add(new VarInsnNode(ASTORE,allocation.index));expr.stackPop();
        add(new VarInsnNode(ALOAD,allocation.index));expr.stackPush(Memory.Type.REFERENCE);
        if (thisExists)writeRuntimeThis();else expr.writePushNull();
        writeContext();
        expr.writeSysDynamicCall(PhpOwnershipClosures.RuntimeAllocation.class,"self",void.class,Memory.class,String.class);
        int index=0;
        for (ArgumentStmtToken parameter:closure.getFunction().getUses()) {
            add(new VarInsnNode(ALOAD,allocation.index));expr.stackPush(Memory.Type.REFERENCE);expr.writePushConstInt(index++);
            LocalVariable local=method.getLocalVariable(parameter.getName().getName());
            if (local == null)expr.writePushNull();else expr.writeVarLoad(local);
            expr.writePopBoxing(!parameter.isReference());
            if (parameter.isReference())expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class,"exportReference",Memory.class,Memory.class);
            expr.writeSysDynamicCall(PhpOwnershipClosures.RuntimeAllocation.class,"capture",void.class,Integer.TYPE,Memory.class);
        }
        add(new VarInsnNode(ALOAD,allocation.index));expr.stackPush(Memory.Type.REFERENCE);
        expr.writeSysDynamicCall(PhpOwnershipClosures.RuntimeAllocation.class,"complete",Memory.class);
        // Both branches leave exactly one semantic Closure VALUE at the common continuation.
        expr.stackPop();add(new JumpInsnNode(GOTO,end));add(passive);return end;
    }

    @Override
    public void write(ClosureStmtToken closure, boolean returnValue) {
        if (returnValue){
            ClosureEntity entity = compiler.getModule().findClosure( closure.getId() );
            boolean thisExists = closure.getFunction().isThisExists();
            boolean staticExists = closure.getFunction().isStaticExists();
            boolean modern = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            if (modern) thisExists = !closure.isStaticClosure() && (thisExists || closure.getOwnerClass() != null);
            int runtimeEval=modern && method.ownership().runtimeEnabled() ? method.ownership().reserveRuntimeEval() : -1;
            if (runtimeEval >= 0)method.ownership().runtimeProducer(runtimeEval,"CLOSURE_LITERAL");
            LabelNode runtimeEnd=runtimeEval < 0 ? null : writeRuntimeProducer(closure,entity,thisExists,runtimeEval);

            if (compiler.getModule().getPhpLanguageProfile() != php.runtime.common.PhpLanguageProfile.PHP_8_5
                    && closure.getFunction().getUses().isEmpty() && !thisExists && !staticExists
                    && closure.getFunction().getStaticLocal().isEmpty()){
                expr.writePushEnv();
                expr.writePushConstString(compiler.getModule().getInternalName());
                expr.writePushConstInt((int) entity.getId());

                writeContext();

                expr.writeSysDynamicCall(
                        Environment.class, "__getSingletonClosure", Memory.class, String.class, Integer.TYPE, String.class
                );
            } else {
                add(new TypeInsnNode(NEW, entity.getInternalName()));
                expr.stackPush(Memory.Type.REFERENCE);
                expr.writePushDup();

                expr.writePushEnv();
                expr.writePushEnv();
                expr.writePushConstString(compiler.getModule().getInternalName());
                expr.writePushConstInt((int) entity.getId());
                expr.writeSysDynamicCall(Environment.class, "__getClosure", ClassEntity.class, String.class, Integer.TYPE);

                if (thisExists) {
                    expr.writePushThis();
                } else {
                    expr.writePushNull();
                }

                writeContext();

                writePushUses(closure.getFunction().getUses(),entity);
                add(new MethodInsnNode(
                        INVOKESPECIAL, entity.getInternalName(), Constants.INIT_METHOD,
                        Type.getMethodDescriptor(
                                Type.getType(void.class),

                                Type.getType(Environment.class),
                                Type.getType(ClassEntity.class),
                                Type.getType(Memory.class),
                                Type.getType(String.class),
                                Type.getType(Memory[].class)
                        ),
                        false
                ));
                expr.stackPop();
                expr.stackPop();
                expr.stackPop();
                expr.stackPop();
                expr.stackPop();
                expr.stackPop();

                if (modern) {
                    expr.writePushDup();
                    expr.writePushConstBoolean(closure.isStaticClosure());
                    expr.writeSysDynamicCall(Closure.class, "setStaticClosure", void.class, Boolean.TYPE);
                    expr.writePushDup();
                    expr.writePushConstBoolean(closure.getFunction().isThisExists());
                    expr.writeSysDynamicCall(Closure.class, "setUsesThis", void.class, Boolean.TYPE);
                }
                expr.writeSysStaticCall(ObjectMemory.class, "valueOf", Memory.class, IObject.class);
                if(compiler.getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5)
                    method.primitiveCells().closureWrapper((MethodInsnNode)node.instructions.getLast());
            }
            if (runtimeEnd != null) {
                add(runtimeEnd);expr.stackPeek().runtimeEvaluationId=runtimeEval;
                expr.stackPeek().runtimeClosureInternalName=entity.getInternalName();
                expr.stackPeek().runtimeClosureProducerId=runtimeEval;
            }
            expr.setStackPeekAsImmutable();
        }
    }
}
