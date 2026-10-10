package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.value.FulledNameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NewExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.ParentExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.SelfExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StaticExprToken;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.cache.ClassCallCache;

public class NewValueCompiler extends BaseExprCompiler<NewExprToken> {
    public NewValueCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    @Override
    public void write(NewExprToken token, boolean returnValue) {
        method.getEntity().setImmutable(false);
        expr.writeLineNumber(token);

        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            boolean constantName = !token.isDynamic() && token.getName() instanceof FulledNameToken
                    && !(token.getName() instanceof ParentExprToken);
            if (token.isDynamic()) expr.writeExpression(token.getExprName(), true, false);
            else if (token.getName() instanceof ParentExprToken || token.getName() instanceof StaticExprToken) {
                expr.writePushEnv();
                expr.writePushTraceInfo(token);
                expr.writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class,
                        token.getName() instanceof ParentExprToken ? "parentClass" : "staticClass", php.runtime.reflection.ClassEntity.class,
                        Environment.class, TraceInfo.class);
                expr.writeSysDynamicCall(php.runtime.reflection.support.Entity.class, "getName", String.class);
            } else if (token.getName() instanceof SelfExprToken) {
                expr.writePushEnv();
                expr.writePushTraceInfo(token);
                expr.writeSysDynamicCall(Environment.class, "__getSelf", String.class, TraceInfo.class);
            } else expr.writePushString(((FulledNameToken) token.getName()).getName());
            expr.writePopBoxing();
            expr.writePushEnv();
            expr.writePushTraceInfo(token);
            if (constantName) {
                expr.writeGetStatic("$CALL_CLASS_CACHE", ClassCallCache.class);
                expr.writePushConstInt(method.clazz.getAndIncCallClassCount());
            } else {
                expr.writePushConstNull();
                expr.writePushConstInt(0);
            }
            expr.writePushConstInt(token.getParameters().size());
            expr.writeSysStaticCall(php.runtime.invoke.PhpCallFrame.class, "construct", php.runtime.invoke.PhpCallFrame.class,
                    Memory.class, Environment.class, TraceInfo.class, ClassCallCache.class, Integer.TYPE, Integer.TYPE);
            expr.writeFrameArguments(token.getParameters(), true);
            expr.setStackPeekAsImmutable();
            if (!returnValue) expr.writePopAll(1);
            return;
        }
        boolean staticName = false;

        expr.writePushEnv();
        if (token.isDynamic()){
            Memory className = expr.writeExpression(token.getExprName(), true, true, false);
            if (className != null) {
                expr.writePushConstString(className.toString());
                expr.writePushConstString(className.toString().toLowerCase());
            } else {
                expr.writeExpression(token.getExprName(), true, false);
                expr.writePopString();
                expr.writePushDupLowerCase();
            }
        } else {
            if (token.getName() instanceof StaticExprToken){
                expr.writePushStatic();
                expr.writePushDupLowerCase();
            } else if (token.getName() instanceof SelfExprToken) {
                expr.writePushEnv();
                expr.writeSysDynamicCall(Environment.class, "__getMacroClass", Memory.class);
                expr.writePopString();
                expr.writePushDupLowerCase();
            } else {
                staticName = true;
                FulledNameToken name = (FulledNameToken) token.getName();
                expr.writePushString(name.getName());
                expr.writePushString(name.getName().toLowerCase());
            }
        }

        expr.writePushTraceInfo(token);
        expr.writePushParameters(token.getParameters());

        if (staticName) {
            int cacheIndex = method.clazz.getAndIncCallClassCount();

            expr.writeGetStatic("$CALL_CLASS_CACHE", ClassCallCache.class);
            expr.writePushConstInt(cacheIndex);

            expr.writeSysDynamicCall(
                    Environment.class, "__newObject",
                    Memory.class,
                    String.class, String.class, TraceInfo.class, Memory[].class, ClassCallCache.class, int.class
            );
        } else {
            expr.writeSysDynamicCall(
                    Environment.class, "__newObject",
                    Memory.class,
                    String.class, String.class, TraceInfo.class, Memory[].class
            );
        }

        expr.setStackPeekAsImmutable();

        if (!returnValue)
            expr.writePopAll(1);
    }
}
