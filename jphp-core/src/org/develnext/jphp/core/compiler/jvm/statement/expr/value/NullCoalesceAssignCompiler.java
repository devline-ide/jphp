package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.ArrayGetExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.ArrayPushExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.develnext.jphp.core.compiler.common.misc.StackItem;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.Memory;
import php.runtime.NullCoalesceAssignment;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

public class NullCoalesceAssignCompiler extends BaseExprCompiler<NullCoalesceAssignExprToken> {
    public NullCoalesceAssignCompiler(ExpressionStmtCompiler expr) { super(expr); }

    @Override public void write(NullCoalesceAssignExprToken token, boolean returnValue) {
        boolean simple = writeTarget(token.getTarget(), token, false);
        LabelNode existing = new LabelNode(), end = new LabelNode();
        Class targetClass = simple ? Memory.class : NullCoalesceAssignment.class;
        expr.writePushDup();
        expr.writeSysDynamicCall(targetClass, simple ? "isNull" : "needsAssignment", boolean.class);
        add(new JumpInsnNode(IFEQ, existing));
        expr.stackPop();
        expr.writeExpression(token.getValue(), true, false);
        expr.writePopBoxing();
        if (simple) expr.writePopImmutable();
        expr.writeSysDynamicCall(targetClass, "assign", Memory.class, Memory.class);
        expr.stackPop();
        add(new JumpInsnNode(GOTO, end));
        add(existing);
        expr.stackPush(Memory.Type.REFERENCE);
        expr.writeSysDynamicCall(targetClass, simple ? "toImmutable" : "value", Memory.class);
        add(end);
        expr.setStackPeekAsImmutable();
        if (!returnValue) expr.writePopAll(1);
    }

    public boolean writeTarget(ExprStmtToken target, Token token, boolean allowAppend) {
        method.getEntity().setImmutable(false);
        List<Token> tokens = target.getTokens();
        int baseEnd = tokens.size();
        while (baseEnd > 0 && (tokens.get(baseEnd - 1) instanceof ArrayGetExprToken
                || allowAppend && tokens.get(baseEnd - 1) instanceof ArrayPushExprToken
                || tokens.get(baseEnd - 1) instanceof DynamicAccessExprToken)) baseEnd--;
        if (baseEnd == 0) expr.unexpectedToken(token);
        Token base = tokens.get(0);
        boolean runtimeEntry=allowAppend && method.ownership().runtimeEnabled() && baseEnd==1
                && base instanceof VariableExprToken && !((VariableExprToken)base).getName().equals("GLOBALS")
                && !((VariableExprToken)base).getName().equals("this") && tokens.size()==2
                && (tokens.get(1) instanceof ArrayPushExprToken || scalarRuntimeIndex(tokens.get(1)));
        boolean runtimeGlobal=allowAppend&&method.ownership().runtimeEnabled()&&baseEnd==1&&base instanceof VariableExprToken
                &&"GLOBALS".equals(((VariableExprToken)base).getName())&&tokens.size()==2&&scalarRuntimeIndex(tokens.get(1))
                && !(((ArrayGetExprToken)tokens.get(1)).getParameters().get(0).getSingle() instanceof StringExprToken
                && ((StringExprToken)((ArrayGetExprToken)tokens.get(1)).getParameters().get(0).getSingle()).hasRawBytes())
                && !(((ArrayGetExprToken)tokens.get(1)).getParameters().get(0).getSingle() instanceof IntegerExprToken
                && ((IntegerExprToken)((ArrayGetExprToken)tokens.get(1)).getParameters().get(0).getSingle()).isBigValue());
        int runtimeGlobalSite=runtimeGlobal?method.ownership().reserveRuntimeEval():-1;
        int runtimeGlobalLocal=runtimeGlobal?method.getLocalVariable("GLOBALS").index:-1;
        MethodInsnNode runtimeGlobalFactory=null,runtimeGlobalIndex=null;String runtimeGlobalKey=null;
        int runtimeSite=runtimeEntry ? method.ownership().reserveRuntimeEval() : -1;
        int runtimeLocal=runtimeEntry ? method.getLocalVariable(((VariableExprToken)base).getName()).index : -1;
        MethodInsnNode runtimeFactory=null;
        boolean simple = tokens.size() == 1 && base instanceof VariableExprToken;
        if (simple && (((VariableExprToken) base).getName().equals("this") || ((VariableExprToken) base).getName().equals("GLOBALS")))
            expr.unexpectedToken(token);
        if (simple) {
            // A local reference already is a complete lvalue. No path object,
            // step list or heap allocation is needed for the common case.
            expr.writePushVariable((VariableExprToken) base);
        } else if (baseEnd == 1 && base instanceof StaticAccessExprToken) {
            StaticAccessExprToken access = (StaticAccessExprToken) base;
            if (access.isDynamicConstant() || !(access.getField() instanceof VariableExprToken) && access.getFieldExpr() == null)
                expr.unexpectedToken(token);
            ValueExprToken clazz = access.getClazz();
            if (clazz instanceof NameToken) expr.writePushConstString(((NameToken) clazz).getName());
            else if (clazz instanceof SelfExprToken) expr.writePushSelf(false);
            else if (clazz instanceof ParentExprToken) expr.writePushParent(clazz);
            else if (clazz instanceof StaticExprToken) expr.writePushStatic();
            else { expr.writePush(clazz, true, false); expr.writePopString(); }
            if (access.getFieldExpr() != null) {
                expr.writeExpression(access.getFieldExpr(), true, false);
                expr.writePopString();
            } else expr.writePushConstString(((VariableExprToken) access.getField()).getName());
            expr.writePushConstBoolean(clazz instanceof StaticExprToken);
            expr.writePushEnv();
            expr.writePushTraceInfo(token);
            expr.writeSysStaticCall(NullCoalesceAssignment.class, "staticProperty", NullCoalesceAssignment.class,
                    String.class, String.class, boolean.class, Environment.class, TraceInfo.class);
        } else {
            boolean variable = baseEnd == 1 && (base instanceof VariableExprToken || base instanceof GetVarExprToken);
            if (baseEnd == tokens.size() && base instanceof VariableExprToken
                    && (((VariableExprToken) base).getName().equals("this") || ((VariableExprToken) base).getName().equals("GLOBALS")))
                expr.unexpectedToken(token);
            if (!variable && baseEnd == tokens.size()) expr.unexpectedToken(token);
            if (baseEnd > 1 && (!(base instanceof BraceExprToken) || !((BraceExprToken) base).isSimpleOpened()
                    || !(tokens.get(baseEnd - 1) instanceof BraceExprToken)
                    || !((BraceExprToken) tokens.get(baseEnd - 1)).isSimpleClosed())) expr.unexpectedToken(token);
            if (baseEnd == 1 && base instanceof VariableExprToken) expr.writePushVariable((VariableExprToken) base);
            else expr.writeExpression(new ExprStmtToken(env, compiler.getContext(),
                    new ArrayList<>(tokens.subList(0, baseEnd))), true, false);
            expr.writePopBoxing();
            expr.writePushEnv();
            expr.writePushTraceInfo(token);
            if(runtimeGlobal) {
                add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));expr.stackPush(Memory.Type.REFERENCE);
                expr.writePushConstInt(runtimeGlobalSite);expr.writePushConstInt(runtimeGlobalLocal);
                expr.writeSysStaticCall(NullCoalesceAssignment.class,"variableGlobalsRuntime",NullCoalesceAssignment.class,
                        Memory.class,Environment.class,TraceInfo.class,PhpRuntimeOwnership.Frame.class,int.class,int.class);
                runtimeGlobalFactory=(MethodInsnNode)method.node.instructions.getLast();
            } else if (runtimeEntry) {
                add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));expr.stackPush(Memory.Type.REFERENCE);
                expr.writePushConstInt(runtimeSite);expr.writePushConstInt(runtimeLocal);
                expr.writeSysStaticCall(NullCoalesceAssignment.class,"variableRuntime",NullCoalesceAssignment.class,
                        Memory.class,Environment.class,TraceInfo.class,PhpRuntimeOwnership.Frame.class,int.class,int.class);
                runtimeFactory=(MethodInsnNode)method.node.instructions.getLast();
            } else expr.writeSysStaticCall(NullCoalesceAssignment.class, "variable", NullCoalesceAssignment.class,
                    Memory.class, Environment.class, TraceInfo.class);
        }
        for (int i = baseEnd; i < tokens.size(); i++) {
            Token step = tokens.get(i);
            if (step instanceof ArrayGetExprToken) {
                for (ExprStmtToken key : ((ArrayGetExprToken) step).getParameters()) {
                    if(runtimeGlobal) {
                        Token literal=key.getSingle();
                        runtimeGlobalKey=literal instanceof StringExprToken?((StringExprToken)literal).getValue()
                                :String.valueOf(((IntegerExprToken)literal).getValue());
                        expr.writePushConstString(runtimeGlobalKey);
                        expr.writeSysDynamicCall(NullCoalesceAssignment.class,"indexGlobal",NullCoalesceAssignment.class,String.class);
                        runtimeGlobalIndex=(MethodInsnNode)method.node.instructions.getLast();continue;
                    }
                    boolean variableKey = allowAppend && key.isSingle() && key.getSingle() instanceof VariableExprToken;
                    if (variableKey) expr.writePushVariable((VariableExprToken) key.getSingle());
                    else expr.writeExpression(key, true, false);
                    expr.writePopBoxing();
                    if (variableKey) {
                        expr.writePushConstString(((VariableExprToken) key.getSingle()).getName());
                        expr.writeSysDynamicCall(NullCoalesceAssignment.class, "indexVariable", NullCoalesceAssignment.class, Memory.class, String.class);
                    } else expr.writeSysDynamicCall(NullCoalesceAssignment.class, "index", NullCoalesceAssignment.class, Memory.class);
                }
            } else if (step instanceof ArrayPushExprToken) {
                expr.writeSysDynamicCall(NullCoalesceAssignment.class, "append", NullCoalesceAssignment.class);
            } else {
                DynamicAccessExprToken property = (DynamicAccessExprToken) step;
                VariableExprToken variableField = property.getField() instanceof VariableExprToken ? (VariableExprToken) property.getField()
                        : property.getFieldExpr() != null && property.getFieldExpr().isSingle() && property.getFieldExpr().getSingle() instanceof VariableExprToken
                        ? (VariableExprToken) property.getFieldExpr().getSingle() : null;
                if (allowAppend && variableField != null) {
                    expr.writePushVariable(variableField);
                    expr.writePopBoxing();
                    expr.writePushConstString(variableField.getName());
                    expr.writeSysDynamicCall(NullCoalesceAssignment.class, "propertyVariable", NullCoalesceAssignment.class, Memory.class, String.class);
                    continue;
                }
                if (property.getFieldExpr() != null) {
                    expr.writeExpression(property.getFieldExpr(), true, false);
                    expr.writePopString();
                } else if (property.getField() instanceof NameToken) {
                    expr.writePushConstString(((NameToken) property.getField()).getName());
                } else { expr.writePush(property.getField(), true, false); expr.writePopString(); }
                expr.writeSysDynamicCall(NullCoalesceAssignment.class, "property", NullCoalesceAssignment.class, String.class);
            }
        }
        if(runtimeGlobal) {
            StackItem result=expr.stackPeek();result.runtimeGlobalSiteId=runtimeGlobalSite;result.runtimeGlobalRootLocalId=runtimeGlobalLocal;
            result.runtimeGlobalKey=runtimeGlobalKey;result.runtimeGlobalFactory=runtimeGlobalFactory;result.runtimeGlobalIndex=runtimeGlobalIndex;
        }
        if (runtimeEntry) {
            StackItem result=expr.stackPeek();result.runtimeArraySiteId=runtimeSite;result.runtimeArrayRootLocalId=runtimeLocal;
            result.runtimeArrayAppend=tokens.get(1) instanceof ArrayPushExprToken;
            result.runtimeArrayFactory=runtimeFactory;result.runtimeArrayStep=(MethodInsnNode)method.node.instructions.getLast();
        }
        return simple;
    }

    private boolean scalarRuntimeIndex(Token token) {
        if (!(token instanceof ArrayGetExprToken))return false;
        List<ExprStmtToken> keys=((ArrayGetExprToken)token).getParameters();
        if (keys.size()!=1 || !keys.get(0).isSingle())return false;
        Token key=keys.get(0).getSingle();
        return key instanceof IntegerExprToken || key instanceof StringExprToken;
    }
}
