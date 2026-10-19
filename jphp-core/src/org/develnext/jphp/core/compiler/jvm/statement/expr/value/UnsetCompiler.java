package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.operator.ArrayGetExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.IntegerExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.GetVarExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.UnsetExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import php.runtime.Memory;
import php.runtime.env.Environment;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class UnsetCompiler extends BaseExprCompiler<UnsetExprToken> {
    public UnsetCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    private boolean writeNamedGlobalUnset(ExprStmtToken target) {
        var tokens=target.getTokens();
        if (!method.ownership().runtimeEnabled() || tokens.size()!=2 || !(tokens.get(0) instanceof VariableExprToken)
                || !"GLOBALS".equals(((VariableExprToken)tokens.get(0)).getName())
                || !(tokens.get(1) instanceof ArrayGetExprToken)) return false;
        var parameters=((ArrayGetExprToken)tokens.get(1)).getParameters();
        if (parameters.size()!=1 || !parameters.get(0).isSingle()) return false;
        Token literal=parameters.get(0).getSingle();String key;
        if (literal instanceof StringExprToken && ((StringExprToken)literal).hasRawBytes()) return false;
        if (literal instanceof StringExprToken) key=((StringExprToken)literal).getValue();
        else if (literal instanceof IntegerExprToken && !((IntegerExprToken)literal).isBigValue()) key=String.valueOf(((IntegerExprToken)literal).getValue());
        else return false;
        LocalVariable root=method.getLocalVariable("GLOBALS");if(root==null)return false;
        int site=method.ownership().reserveRuntimeEval();
        add(new VarInsnNode(Opcodes.ALOAD,method.ownership().runtimeFrameIndex()));expr.stackPush(Memory.Type.REFERENCE);
        expr.writePushConstInt(site);expr.writePushConstInt(root.index);
        VarInsnNode read=new VarInsnNode(Opcodes.ALOAD,root.index);add(read);expr.stackPush(Memory.Type.REFERENCE);
        expr.writePushConstString(key);expr.writePushEnv();
        expr.writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"globalUnset",void.class,
                php.runtime.PhpRuntimeOwnership.Frame.class,int.class,int.class,Memory.class,String.class,Environment.class);
        method.ownership().runtimeGlobalUnset(site,root.index,key,(MethodInsnNode)method.node.instructions.getLast(),read);
        expr.invalidateGlobalVariable(key);
        return true;
    }

    @Override
    public void write(UnsetExprToken token, boolean returnValue) {
        method.getEntity().setImmutable(false);
        for(ExprStmtToken param : token.getParameters()){
            if (writeNamedGlobalUnset(param)) continue;
            if (param.isSingle() && param.getSingle() instanceof VariableExprToken){
                VariableExprToken variable = (VariableExprToken)param.getSingle();
                expr.checkAssignableVar(variable);
                LocalVariable local = method.getLocalVariable(variable.getName());

                if (local.isReference() && method.ownership().runtimeEnabled()) {
                    add(new VarInsnNode(Opcodes.ALOAD, method.ownership().runtimeFrameIndex()));
                    add(new LdcInsnNode(local.index));
                    expr.writeVarLoad(local);
                    expr.writePushEnv();
                    add(new MethodInsnNode(Opcodes.INVOKESTATIC, "php/runtime/PhpRuntimeOwnership", "unsetLocal",
                            "(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/Memory;Lphp/runtime/env/Environment;)V", false));
                    expr.stackPop();
                    expr.stackPop();
                } else {
                    expr.writeVarLoad(local);
                    expr.writePushEnv();
                    expr.writeSysDynamicCall(Memory.class, "manualUnset", void.class, Environment.class);
                }
                if (!local.isReference()) {
                    expr.writePushNull();
                    expr.writeVarAssign(local, null, false, false);
                }

                local.setValue(null);
            } else if (param.isSingle() && param.getSingle() instanceof GetVarExprToken){
                expr.writeValue((ValueExprToken) param.getSingle(), true);
                expr.writePushEnv();
                expr.writeSysDynamicCall(Memory.class, "manualUnset", void.class, Environment.class);
            } else {
                expr.writeExpression(param, false, false, true);
            }
        }

        if (returnValue)
            expr.writePushNull();
    }
}
