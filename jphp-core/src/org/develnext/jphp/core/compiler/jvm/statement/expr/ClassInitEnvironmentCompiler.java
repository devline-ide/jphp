package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;

public class ClassInitEnvironmentCompiler extends BaseStatementCompiler<JvmCompiler.ClassInitEnvironment> {
    public ClassInitEnvironmentCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    @Override
    public void write(JvmCompiler.ClassInitEnvironment token) {
        if(compiler.getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5
                && !token.getEntity().isUnresolvedDeclaration()) {
            expr.writePushEnv();expr.writePushTraceInfo(token.getToken());
            expr.writePushConstString(compiler.getModule().getInternalName());
            expr.writePushConstString(token.getEntity().getName());
            expr.writeSysDynamicCall(Environment.class,"__checkClassDeclaration",void.class,
                    php.runtime.env.TraceInfo.class,String.class,String.class);
            if(!token.isInitializationRequired())return;
        }
        expr.writePushEnv();
        if (token.getEntity().isUnresolvedDeclaration()) {
            expr.writePushConstString(compiler.getModule().getInternalName());
            expr.writePushConstString(token.getEntity().getName());
            expr.writeSysDynamicCall(Environment.class,"__declareClass",ClassEntity.class,String.class,String.class);
            expr.writePopAll(1);
            return;
        }
        expr.writePushConstString(token.getEntity().getName());
        expr.writePushConstString(token.getEntity().getLowerName());
        expr.writePushScalarBoolean(false);
        expr.writeSysDynamicCall(
                Environment.class, "fetchClass", ClassEntity.class, String.class, String.class, Boolean.TYPE
        );
        expr.writePushEnv();
        expr.writeSysDynamicCall(ClassEntity.class, "initEnvironment", void.class, Environment.class);
    }
}
