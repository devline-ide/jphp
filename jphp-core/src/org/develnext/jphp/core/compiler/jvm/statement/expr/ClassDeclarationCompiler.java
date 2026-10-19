package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.reflection.ClassEntity;

/** Executed named declaration: the module ordinal identifies the actual physical template. */
public final class ClassDeclarationCompiler extends BaseStatementCompiler<ClassStmtToken> {
    public ClassDeclarationCompiler(ExpressionStmtCompiler compiler) {super(compiler);}
    @Override public void write(ClassStmtToken token) {
        if (!token.isDeferredDeclaration()) throw new IllegalArgumentException("Uncatalogued named class declaration");
        expr.writePushEnv();expr.writePushTraceInfo(token);
        expr.writePushConstString(compiler.getModule().getInternalName());expr.writePushConstInt(token.getDeclarationId());
        expr.writeSysDynamicCall(Environment.class,"__defineClass",ClassEntity.class,TraceInfo.class,String.class,Integer.TYPE);
        expr.writePopAll(1);
    }
}
