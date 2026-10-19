package org.develnext.jphp.core.compiler.jvm.statement.expr.operator;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.OperatorExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.objectweb.asm.Opcodes.ALOAD;
import php.runtime.Memory;
import php.runtime.common.Association;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.invoke.cache.PropertyCallCache;

public class DynamicAccessCompiler extends BaseExprCompiler<DynamicAccessExprToken> {
    public DynamicAccessCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    /** A literal declared FIELD reference consumes its actual witnessed object CV. */
    private boolean writeRuntimePropertyReference(DynamicAccessExprToken dynamic, boolean returnValue) {
        if (method.statement!=null&&method.statement.getPropertyHookName()!=null)return false;
        if (!method.ownership().runtimeEnabled() || expr.isRuntimeFieldReferenceBinding() || expr.isRuntimeReferenceIterator()
                || dynamic.getClass()!=DynamicAccessGetRefExprToken.class
                || dynamic.getFieldExpr()!=null || !(dynamic.getField() instanceof NameToken)
                || !(expr.stackPeek().getToken() instanceof VariableExprToken)) return false;
        var root=method.getLocalVariable(((VariableExprToken)expr.stackPeek().getToken()).getName());
        if (root==null || !root.isReference() || !root.isOwnershipLocal()
                || method.ownership().runtimeObjectLocal(root.index)==null) return false;
        String key=dynamic.getField().getWord();
        expr.stackPop();
        int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"PROPERTY_REF_RESULT");
        add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));expr.stackPush(Memory.Type.REFERENCE);
        expr.writePushConstInt(id);expr.writePushConstInt(root.index);
        VarInsnNode read=new VarInsnNode(ALOAD,root.index);add(read);expr.stackPush(Memory.Type.REFERENCE);
        expr.writePushConstString(key);expr.writePushEnv();expr.writePushTraceInfo(dynamic);
        expr.writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"propertyReference",Memory.class,
                php.runtime.PhpRuntimeOwnership.Frame.class,int.class,int.class,Memory.class,String.class,
                Environment.class,TraceInfo.class);
        method.ownership().runtimePropertyReference(id,root.index,key,(MethodInsnNode)node.instructions.getLast(),read);
        expr.stackPeek().runtimeEvaluationId=id;
        if (!returnValue) expr.writePopAll(1);
        return true;
    }

    private void hookAwareCall(Class<?> owner,String name,Class<?> result,Class<?>... parameters){
        if(method.statement==null||method.statement.getPropertyHookName()==null){expr.writeSysStaticCall(owner,name,result,parameters);return;}
        expr.writeVarLoad("~propertyHook");expr.writeVarLoad(method.getGeneratorEntity()==null?"~this":"~propertyHookThis");
        Class<?>[] extended=java.util.Arrays.copyOf(parameters,parameters.length+2);
        extended[parameters.length]=php.runtime.reflection.support.PhpPropertyHooks.Hook.class;
        extended[parameters.length+1]=php.runtime.lang.IObject.class;
        expr.writeSysStaticCall(php.runtime.PhpPropertyHookAccess.class,name,result,extended);
    }

    @Override
    public void write(DynamicAccessExprToken dynamic, boolean returnValue) {
        if (writeRuntimePropertyReference(dynamic,returnValue)) return;
        expr.writeDynamicAccessPrepare(dynamic, false);

        if (dynamic.getFieldExpr() == null && dynamic.getField() instanceof NameToken && !method.clazz.statement.isTrait()) {
            expr.writeGetStatic("$CALL_PROP_CACHE", PropertyCallCache.class);
            expr.writePushConstInt(method.clazz.getAndIncCallPropCount());
        } else {
            expr.writePushConstNull();
            expr.writePushConstInt(0);
        }

        if (dynamic instanceof DynamicAccessAssignExprToken){
            OperatorExprToken operator = (OperatorExprToken) ((DynamicAccessAssignExprToken) dynamic).getAssignOperator();
            String code = operator.getCode();
            if (operator instanceof IncExprToken || operator instanceof DecExprToken) {
                if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    expr.writePushConstBoolean(operator instanceof DecExprToken);
                    expr.writePushConstBoolean(operator.getAssociation() == Association.LEFT);
                    hookAwareCall(php.runtime.PhpArithmetic.class, "incrementProperty", Memory.class,
                            Memory.class, String.class, Environment.class, TraceInfo.class,
                            PropertyCallCache.class, int.class, boolean.class, boolean.class);
                } else {
                    if (operator.getAssociation() == Association.RIGHT)
                        code = code + "AndGet";
                    else
                        code = "GetAnd" + code.substring(0, 1).toUpperCase() + code.substring(1);

                    hookAwareCall(ObjectInvokeHelper.class,
                            code + "Property", Memory.class,
                            Memory.class, String.class, Environment.class, TraceInfo.class,
                            PropertyCallCache.class, int.class
                    );
                }
            } else {
                if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                        && org.develnext.jphp.core.compiler.common.util.CompilerUtils.isPhpArithmetic(operator)) {
                    expr.writePushConstString(((AssignOperatorExprToken) operator).getOperatorCode());
                    hookAwareCall(php.runtime.PhpArithmetic.class, "assignProperty", Memory.class,
                            Memory.class, Memory.class, String.class, Environment.class, TraceInfo.class,
                            PropertyCallCache.class, int.class, String.class);
                } else hookAwareCall(ObjectInvokeHelper.class,
                        code + "Property", Memory.class,
                        Memory.class, Memory.class, String.class, Environment.class, TraceInfo.class,
                        PropertyCallCache.class, int.class
                );
            }
        } else if (dynamic instanceof DynamicAccessUnsetExprToken){
            hookAwareCall(ObjectInvokeHelper.class,
                    "unsetProperty", void.class,
                    Memory.class, String.class, Environment.class, TraceInfo.class,
                    PropertyCallCache.class, int.class
            );

            if (returnValue)
                expr.writePushNull();
        } else if (dynamic instanceof DynamicAccessEmptyExprToken){
            hookAwareCall(ObjectInvokeHelper.class,
                    "emptyProperty", Memory.class,
                    Memory.class, String.class, Environment.class, TraceInfo.class,
                    PropertyCallCache.class, int.class
            );
        } else if (dynamic instanceof DynamicAccessIssetExprToken){
            hookAwareCall(ObjectInvokeHelper.class,
                    "issetProperty", Memory.class,
                    Memory.class, String.class, Environment.class, TraceInfo.class,
                    PropertyCallCache.class, int.class
            );
        } else {
            if (expr.writeArgumentReferenceMode(dynamic)) {
                expr.writeSysStaticCall(php.runtime.invoke.PhpCallFrame.class,
                        expr.isArgumentArrayProperty(dynamic) ? "argumentArrayProperty" : "argumentProperty",Memory.class,
                        Memory.class,String.class,Environment.class,TraceInfo.class,
                        PropertyCallCache.class,int.class,boolean.class);
                return;
            }
            hookAwareCall(ObjectInvokeHelper.class,
                    dynamic.isArrayUnsetRead() ? "getQuietArrayUnsetProperty" : dynamic.isCoalescingRead() ? "getQuietProperty" : dynamic instanceof DynamicAccessGetRefExprToken ? "getRefProperty" : dynamic.isObjectWriteReceiverRead() ? "getObjectWriteReceiverProperty" : "getProperty",
                    Memory.class,
                    Memory.class, String.class, Environment.class, TraceInfo.class,
                    PropertyCallCache.class, int.class
            );
        }
    }
}
