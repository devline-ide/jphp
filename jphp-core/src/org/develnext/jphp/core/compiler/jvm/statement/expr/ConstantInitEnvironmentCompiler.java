package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import php.runtime.Memory;
import php.runtime.env.Environment;

/** Declaration-time acquisition; no Closure objects are registered or dumped by the compiler. */
public final class ConstantInitEnvironmentCompiler extends BaseStatementCompiler<JvmCompiler.ConstantInitEnvironment> {
    public ConstantInitEnvironmentCompiler(ExpressionStmtCompiler expr) { super(expr); }
    @Override public void write(JvmCompiler.ConstantInitEnvironment token) {
        expr.writePushEnv();
        expr.writePushConstString(expr.getCompiler().getModule().getInternalName());
        expr.writePushConstString(token.item.getFulledName());
        expr.writeExpression(token.item.value, true, false);
        expr.writePopBoxing(true);
        expr.writeSysDynamicCall(Environment.class, "defineDeclaredConstant", boolean.class, String.class, String.class, Memory.class);
        expr.writePopAll(1);
    }
}
