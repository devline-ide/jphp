package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.value.MatchExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.objectweb.asm.tree.*;
import php.runtime.Memory;
import php.runtime.PhpMatch;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import static org.objectweb.asm.Opcodes.*;

public class MatchValueCompiler extends BaseExprCompiler<MatchExprToken> {
    public MatchValueCompiler(ExpressionStmtCompiler expr) { super(expr); }
    private void invalidateValues() { for(var local:method.getLocalVariables().values()) {local.setValue(null);local.clearRuntimeClosureWitness();} }
    private void clear(LocalVariable local) { add(new InsnNode(ACONST_NULL));add(new VarInsnNode(ASTORE,local.index)); }
    @Override public void write(MatchExprToken token, boolean returnValue) {
        if(method.ownership().runtimeEnabled()) method.ownership().runtimeUnsupported("match-expression");
        var outerGuards=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TryCatchBlockNode,Boolean>());
        outerGuards.addAll(node.tryCatchBlocks);
        method.getEntity().setImmutable(false);invalidateValues();
        var start=new LabelNode();var joined=new LabelNode();var handler=new LabelNode();var end=new LabelNode();add(start);
        int id=method.nextStatementIndex(Memory.class);
        var subject=method.addLocalVariable("~match-subject~"+id,start,Memory.class);
        var condition=method.addLocalVariable("~match-condition~"+id,start,Memory.class);
        var failure=method.addLocalVariable("~match-failure~"+id,start,Throwable.class);
        subject.setEndLabel(end);condition.setEndLabel(end);failure.setEndLabel(end);
        clear(subject);clear(condition);
        expr.writeExpression(token.getSubject(),true,false);expr.writePopBoxing();expr.writeVarStore(subject,false,false);
        LocalVariable live=null;
        var ast=token.getSubject().getAsmExpr();
        if(ast!=null && ast.isSingle() && ast.getSingle() instanceof VariableExprToken variable) {
            var local=method.getLocalVariable(variable.getName());
            if(local!=null && Memory.class.isAssignableFrom(local.getClazz())) live=local;
        }
        var guarded=new LabelNode();add(guarded);
        var labels=new java.util.ArrayList<LabelNode>();LabelNode fallback=null;
        for(var arm:token.getArms()) {
            var label=new LabelNode();labels.add(label);
            if(arm.isDefault()) {fallback=label;continue;}
            for(var value:arm.conditions()) {
                invalidateValues();expr.writeExpression(value,true,false);expr.writePopBoxing();expr.writeVarStore(condition,false,false);
                expr.writeVarLoad(live==null?subject:live);expr.writePopBoxing();expr.writeVarLoad(condition);
                expr.writeSysDynamicCall(Memory.class,"identical",boolean.class,Memory.class);
                add(new JumpInsnNode(IFNE,label));expr.stackPop();clear(condition);
            }
        }
        if(fallback!=null) add(new JumpInsnNode(GOTO,fallback));
        else {
            expr.writeVarLoad(live==null?subject:live);expr.writePopBoxing();expr.writePushEnv();expr.writePushTraceInfo(token);
            expr.writeSysStaticCall(PhpMatch.class,"unhandled",Memory.class,Memory.class,Environment.class,TraceInfo.class);
            expr.stackPop();add(new JumpInsnNode(GOTO,joined));
        }
        int i=0;
        for(var arm:token.getArms()) {
            add(labels.get(i++));clear(condition);invalidateValues();
            expr.writeExpression(arm.result(),true,false);expr.writePopBoxing();expr.writePopImmutable();
            expr.stackPop();add(new JumpInsnNode(GOTO,joined));
        }
        add(joined);clear(subject);clear(condition);add(new JumpInsnNode(GOTO,end));
        add(handler);add(new VarInsnNode(ASTORE,failure.index));clear(subject);clear(condition);
        add(new VarInsnNode(ALOAD,failure.index));clear(failure);add(new InsnNode(ATHROW));
        int guardIndex=0;
        while(guardIndex<node.tryCatchBlocks.size() && !outerGuards.contains(node.tryCatchBlocks.get(guardIndex))) guardIndex++;
        node.tryCatchBlocks.add(guardIndex,new TryCatchBlockNode(guarded,joined,handler,"java/lang/Throwable"));
        add(end);expr.stackPush(Memory.Type.REFERENCE);expr.setStackPeekAsImmutable();
        if(!returnValue) expr.writePopAll(1);
        invalidateValues();method.prevStatementIndex(Memory.class);
    }
}
