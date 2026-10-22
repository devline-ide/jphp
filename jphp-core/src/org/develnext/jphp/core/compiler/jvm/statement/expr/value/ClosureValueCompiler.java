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

    private void writeCaptureRead(ArgumentStmtToken parameter,boolean explicit) {writeCaptureRead(parameter,explicit,true);}
    private void writeCaptureRead(ArgumentStmtToken parameter,boolean explicit,boolean diagnose) {
        LocalVariable local=method.getLocalVariable(parameter.getName().getName());
        boolean warn=explicit && !parameter.isReference()
                && compiler.getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5;
        if(local!=null)expr.writeVarLoad(local);
        else if(warn && methodStatement.isDynamicLocal()) {
            expr.writePushLocal();expr.writePushConstString(parameter.getName().getName());
            expr.writeSysDynamicCall(Memory.class,"valueOfIndex",Memory.class,String.class);
        } else if(warn)expr.writePushMemory(Memory.UNDEFINED);
        else expr.writePushNull();
        if(warn && diagnose) {
            // One compact call preserves creation-time reads without copying a warning branch per capture.
            expr.writePushEnv();expr.writePushCaptureTrace(parameter.getName());
            expr.writePushConstString(parameter.getName().getName());
            expr.writeSysStaticCall(PhpOwnershipClosures.class,"readValueCapture",Memory.class,
                    Memory.class,Environment.class,php.runtime.env.TraceInfo[].class,Integer.TYPE,String.class);
            // The helper already produced the immutable VALUE snapshot.
            expr.setStackPeekAsImmutable();
        }
    }
    protected void writePushUses(Collection<ArgumentStmtToken> parameters){writePushUses(parameters,null,false);}
    protected void writePushUses(Collection<ArgumentStmtToken> parameters,ClosureEntity closure){writePushUses(parameters,closure,false);}
    private void writePushUses(Collection<ArgumentStmtToken> parameters,ClosureEntity closure,boolean explicit){
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
            writeCaptureRead(param,explicit);

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
            boolean explicitValue=!closure.getFunction().isAutoUses()&&!parameter.isReference();
            boolean dynamicLookup=explicitValue&&method.getLocalVariable(parameter.getName().getName())==null&&methodStatement.isDynamicLocal();
            boolean moduleLookup=dynamicLookup&&method.isExternal()&&method.clazz.isSystem()&&methodStatement.getName().getName().equals("__include");
            if(moduleLookup) {
                // The producer reads the exact module table without declaring or exporting a missing CV.
                expr.writePushLocal();expr.writePushCaptureTrace(parameter.getName());
                expr.writeSysDynamicCall(PhpOwnershipClosures.RuntimeAllocation.class,"capture",void.class,
                        Integer.TYPE,php.runtime.memory.ArrayMemory.class,php.runtime.env.TraceInfo[].class,Integer.TYPE);
                continue;
            }
            if(dynamicLookup)method.ownership().runtimeUnsupported("dynamic-function-value-capture-source:"+parameter.getName().getName());
            writeCaptureRead(parameter,!closure.getFunction().isAutoUses(),false);
            // Explicit VALUE capture snapshots inside its actual producer helper.
            expr.writePopBoxing(!parameter.isReference()&&!explicitValue);
            if (parameter.isReference())expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class,"exportReference",Memory.class,Memory.class);
            if(explicitValue) {
                expr.writePushCaptureTrace(parameter.getName());
                expr.writeSysDynamicCall(PhpOwnershipClosures.RuntimeAllocation.class,"capture",void.class,
                        Integer.TYPE,Memory.class,php.runtime.env.TraceInfo[].class,Integer.TYPE);
            } else expr.writeSysDynamicCall(PhpOwnershipClosures.RuntimeAllocation.class,"capture",void.class,Integer.TYPE,Memory.class);
        }
        add(new VarInsnNode(ALOAD,allocation.index));expr.stackPush(Memory.Type.REFERENCE);
        expr.writeSysDynamicCall(PhpOwnershipClosures.RuntimeAllocation.class,"complete",Memory.class);
        // Both branches leave exactly one semantic Closure VALUE at the common continuation.
        expr.stackPop();add(new JumpInsnNode(GOTO,end));add(passive);return end;
    }

    /** Session arm is physically separate from both Authority and ordinary construction. */
    private LabelNode writeSessionProducer(ClosureStmtToken closure,ClosureEntity entity,int evaluation) {
        LabelNode passive=method.ownership().guardActive(node.instructions),active=new LabelNode(),end=new LabelNode();
        add(active);method.ownership().beginRuntimeActiveEmission();
        LocalVariable allocation=method.addLocalVariable("~sessionClosure"+evaluation,null,Object.class);
        add(new VarInsnNode(ALOAD,method.ownership().entryIndex()));expr.stackPush(Memory.Type.REFERENCE);
        expr.writePushConstInt(evaluation);expr.writePushConstInt((int)entity.getId());writeContext();
        expr.writeSysDynamicCall(php.runtime.PhpOwnershipExecution.Entry.class,"prepareClosure",PhpOwnershipClosures.SessionAllocation.class,Integer.TYPE,Integer.TYPE,String.class);
        add(new VarInsnNode(ASTORE,allocation.index));expr.stackPop();
        int index=0;
        for(ArgumentStmtToken parameter:closure.getFunction().getUses()) {
            LocalVariable local=method.getLocalVariable(parameter.getName().getName());
            add(new VarInsnNode(ALOAD,allocation.index));expr.stackPush(Memory.Type.REFERENCE);
            expr.writePushConstInt(index++);expr.writePushConstInt(local==null?-1:local.index);
            // No planned CV means an absent capture, including debug's locals-table representation.
            // Entry verifies that absence before owning NULL and issuing the warning.
            if(local==null)expr.writePushMemory(Memory.UNDEFINED);else writeCaptureRead(parameter,true,false);
            expr.writePopBoxing(false);
            if(parameter.isReference()) {
                expr.writeSysDynamicCall(PhpOwnershipClosures.SessionAllocation.class,"captureReference",void.class,
                        Integer.TYPE,Integer.TYPE,Memory.class);
                method.ownership().closureProducerCall((MethodInsnNode)node.instructions.getLast());continue;
            }
            expr.writePushCaptureTrace(parameter.getName());
            expr.writeSysDynamicCall(PhpOwnershipClosures.SessionAllocation.class,"capture",void.class,
                    Integer.TYPE,Integer.TYPE,Memory.class,php.runtime.env.TraceInfo[].class,Integer.TYPE);
            method.ownership().closureProducerCall((MethodInsnNode)node.instructions.getLast());
        }
        add(new VarInsnNode(ALOAD,allocation.index));expr.stackPush(Memory.Type.REFERENCE);
        expr.writeSysDynamicCall(PhpOwnershipClosures.SessionAllocation.class,"complete",Memory.class);
        method.ownership().closureProducerCall((MethodInsnNode)node.instructions.getLast());
        expr.stackPop();add(new JumpInsnNode(GOTO,end));add(passive);
        method.ownership().endRuntimeActiveEmission();method.ownership().runtimeActiveBranch(active,passive);
        method.ownership().beginPassive();return end;
    }
    @Override
    public void write(ClosureStmtToken closure, boolean returnValue) {
        if (returnValue){
            ClosureEntity entity = compiler.getModule().findClosure( closure.getId() );
            boolean thisExists = closure.getFunction().isThisExists();
            boolean staticExists = closure.getFunction().isStaticExists();
            boolean modern = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            if (modern) thisExists = !closure.isStaticClosure() && (thisExists || closure.getOwnerClass() != null);
            boolean sessionValue=modern && method.ownership().enabled() && closure.isStaticClosure() && !thisExists
                    && closure.getFunction().getStaticLocal().isEmpty() && !closure.getFunction().isAutoUses()
                    && !closure.getFunction().isGenerator();
            int sessionEval=sessionValue?method.ownership().closureEvaluation((int)entity.getId(),closure.getFunction().getUses().stream().anyMatch(capture->capture.isReference())):-1;
            LabelNode sessionEnd=sessionValue?writeSessionProducer(closure,entity,sessionEval):null;
            LabelNode sessionPassive=sessionEnd==null?null:(LabelNode)node.instructions.getLast();
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
                    if (modern) writeRuntimeThis();
                    else expr.writePushThis();
                } else {
                    expr.writePushNull();
                }

                writeContext();

                writePushUses(closure.getFunction().getUses(),entity,!closure.getFunction().isAutoUses());
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
            if(sessionEnd!=null){
                add(sessionEnd);method.ownership().endPassive(sessionPassive,sessionEnd);
                expr.stackPeek().ownershipEvalId=sessionEval;
            }
            expr.setStackPeekAsImmutable();
        }
    }
}
