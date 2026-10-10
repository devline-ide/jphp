package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.ClassExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.invoke.cache.ConstantCallCache;
import php.runtime.invoke.cache.MethodCallCache;
import php.runtime.invoke.cache.PropertyCallCache;

public class StaticAccessValueCompiler extends BaseExprCompiler<StaticAccessExprToken> {
    public StaticAccessValueCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    private void writeModernConstant(StaticAccessExprToken token, boolean returnValue) {
        ValueExprToken clazz = token.getClazz();
        if (token.isDynamicConstant() && (clazz instanceof ParentExprToken || clazz instanceof StaticExprToken || clazz instanceof SelfExprToken)) {
            // Relative scope errors occur after the name expression, unlike an ordinary class fetch.
            expr.writeExpression(token.getFieldExpr(), true, false);
            expr.writePopBoxing();
            expr.writePushEnv();
            expr.writePushTraceInfo(token);
            expr.writePushConstString(clazz instanceof ParentExprToken ? "parent" : clazz instanceof StaticExprToken ? "static" : "self");
            expr.writeGetStatic("$CALL_CONST_CACHE", ConstantCallCache.class);
            expr.writePushConstInt(method.clazz.getAndIncCallConstCount());
            expr.writeSysStaticCall(ObjectInvokeHelper.class, "getRelativeConstant", Memory.class,
                    Memory.class, Environment.class, TraceInfo.class, String.class, ConstantCallCache.class, Integer.TYPE);
            expr.setStackPeekAsImmutable();
            if (!returnValue) expr.writePopAll(1);
            return;
        }
        if (clazz instanceof ParentExprToken || clazz instanceof StaticExprToken) {
            expr.writePushEnv();
            expr.writePushTraceInfo(token);
            expr.writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class,
                    clazz instanceof ParentExprToken ? "parentClass" : "staticClass", php.runtime.reflection.ClassEntity.class,
                    Environment.class, TraceInfo.class);
        } else {
            if (clazz instanceof SelfExprToken) expr.writePushSelf(false);
            else if (clazz instanceof NameToken) expr.writePushString(((NameToken) clazz).getName());
            else if (clazz != null) expr.writePush(clazz, true, false);
            expr.writePopBoxing();
            expr.writePushEnv();
            expr.writePushTraceInfo(token);
            expr.writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class, "resolveClass", php.runtime.reflection.ClassEntity.class,
                    Memory.class, Environment.class, TraceInfo.class);
        }
        if (token.isDynamicConstant()) expr.writeExpression(token.getFieldExpr(), true, false);
        else expr.writePushString(((NameToken) token.getField()).getName());
        expr.writePopBoxing();
        expr.writePushEnv();
        expr.writePushTraceInfo(token);
        expr.writeGetStatic("$CALL_CONST_CACHE", ConstantCallCache.class);
        expr.writePushConstInt(method.clazz.getAndIncCallConstCount());
        expr.writePushConstBoolean(clazz instanceof StaticExprToken);
        String reference = clazz instanceof SelfExprToken ? "self" : clazz instanceof ParentExprToken ? "parent"
                : clazz instanceof StaticExprToken ? "static" : clazz instanceof NameToken ? ((NameToken) clazz).getName() : null;
        if (reference == null) expr.writePushConstNull(); else expr.writePushConstString(reference);
        expr.writeSysStaticCall(ObjectInvokeHelper.class, "getConstant", Memory.class,
                php.runtime.reflection.ClassEntity.class, Memory.class, Environment.class, TraceInfo.class,
                ConstantCallCache.class, Integer.TYPE, boolean.class, String.class);
        expr.setStackPeekAsImmutable();
        if (!returnValue) expr.writePopAll(1);
    }

    @Override
    public void write(StaticAccessExprToken token, boolean returnValue) {
        boolean isConstant = token.getField() instanceof NameToken;
        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && (isConstant || token.isDynamicConstant())) {
            writeModernConstant(token, returnValue);
            return;
        }
        /*if (token.getField() == null)
            expr.unexpectedToken(token.getFieldExpr().getSingle());*/

        boolean lateStaticCall = false;

        if (token.getField() instanceof ClassExprToken) {
            if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                if (token.getClazz() instanceof ParentExprToken || token.getClazz() instanceof StaticExprToken || token.getClazz() instanceof SelfExprToken) {
                    expr.writePushEnv();
                    expr.writePushTraceInfo(token);
                    expr.writePushConstString(token.getClazz() instanceof ParentExprToken ? "parent"
                            : token.getClazz() instanceof StaticExprToken ? "static" : "self");
                    expr.writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class, "relativeClassName", String.class,
                            Environment.class, TraceInfo.class, String.class);
                } else {
                    if (token.getClazz() != null) expr.writePush(token.getClazz(), true, false);
                    expr.writePopBoxing();
                    expr.writePushEnv();
                    expr.writePushTraceInfo(token);
                    expr.writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class, "objectClassName", String.class,
                            Memory.class, Environment.class, TraceInfo.class);
                }
            } else if (token.getClazz() instanceof ParentExprToken) {
                expr.writePushParent(token.getClazz());
            } else if (token.getClazz() instanceof StaticExprToken) {
                expr.writePushStatic();
                lateStaticCall = true;
            } else if (token.getClazz() instanceof SelfExprToken) {
                expr.writePushSelf(false);
            } else
                expr.unexpectedToken(token);
        } else {
            ValueExprToken clazz = token.getClazz();

            if (clazz instanceof ParentExprToken){
                expr.writePushParent(clazz);
                expr.writePushDupLowerCase();
            } else if (clazz instanceof NameToken){
                expr.writePushConstString(((NameToken) clazz).getName());
                expr.writePushConstString(((NameToken) clazz).getName().toLowerCase(java.util.Locale.ROOT));
            } else {
                if (clazz instanceof StaticExprToken){
                    expr.writePushStatic();
                    lateStaticCall = true;
                } else {
                    if (clazz != null) {
                        expr.writePush(clazz, true, false);
                    } else {
                        // it static access operator. skip.
                    }
                }

                if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    expr.writePopBoxing();expr.writePushEnv();expr.writePushTraceInfo(token);
                    expr.writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class,"resolveClass",php.runtime.reflection.ClassEntity.class,
                            Memory.class,Environment.class,TraceInfo.class);
                    expr.writeSysDynamicCall(php.runtime.reflection.support.Entity.class,"getName",String.class);
                } else expr.writePopString();
                expr.writePushDupLowerCase();
            }

            if (isConstant) {
                expr.writePushConstString(((NameToken) token.getField()).getName());
                expr.writePushEnv();
                expr.writePushTraceInfo(token);

                int cacheIndex = method.clazz.getAndIncCallConstCount();
                expr.writeGetStatic("$CALL_CONST_CACHE", ConstantCallCache.class);
                expr.writePushConstInt(cacheIndex);
                expr.writePushConstBoolean(lateStaticCall);

                expr.writeSysStaticCall(ObjectInvokeHelper.class, "getConstant",
                        Memory.class, String.class, String.class, String.class, Environment.class, TraceInfo.class,
                        ConstantCallCache.class, Integer.TYPE, boolean.class
                );
                expr.setStackPeekAsImmutable();
            } else {
                if (token.getFieldExpr() != null) {
                    expr.writeExpression(token.getFieldExpr(), true, false);
                    expr.writePopString();
                } else {
                    if (!(token.getField() instanceof VariableExprToken))
                        expr.unexpectedToken(token.getField());

                    expr.writePushConstString(((VariableExprToken) token.getField()).getName());
                }
                expr.writePushEnv();
                expr.writePushTraceInfo(token);

                String name = "get";
                if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)
                    name=token.isQuietRead() ? "isset" : token.isWriteAccess() ? "get" : "read";
                if (token instanceof StaticAccessUnsetExprToken)
                    name = "unset";
                else if (token instanceof StaticAccessIssetExprToken)
                    name = "isset";

                if (token.getFieldExpr() == null && token.getField() instanceof NameToken) {
                    expr.writeGetStatic("$CALL_PROP_CACHE", PropertyCallCache.class);
                    expr.writePushConstInt(method.clazz.getAndIncCallPropCount());
                } else {
                    expr.writePushConstNull();
                    expr.writePushConstInt(0);
                }

                expr.writePushConstBoolean(lateStaticCall);
                if (expr.writeArgumentReferenceMode(token)) {
                    expr.writeSysStaticCall(php.runtime.invoke.PhpCallFrame.class,
                            expr.isArgumentArrayProperty(token) ? "argumentArrayStaticProperty" : "argumentStaticProperty",Memory.class,
                            String.class,String.class,String.class,Environment.class,TraceInfo.class,
                            PropertyCallCache.class,int.class,boolean.class,boolean.class);
                    if (!returnValue) expr.writePopAll(1);
                    return;
                }
                expr.writeSysStaticCall(ObjectInvokeHelper.class,
                        name + "StaticProperty",
                        Memory.class, String.class, String.class, String.class, Environment.class, TraceInfo.class,
                        PropertyCallCache.class, int.class, boolean.class
                );
            }
        }

        if (!returnValue)
            expr.writePopAll(1);
    }
}
