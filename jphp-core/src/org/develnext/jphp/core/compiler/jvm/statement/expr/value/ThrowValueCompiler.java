package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.value.ThrowExprToken;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;

public class ThrowValueCompiler extends BaseExprCompiler<ThrowExprToken> {
    public ThrowValueCompiler(ExpressionStmtCompiler expr) { super(expr); }
    @Override public void write(ThrowExprToken token, boolean returnValue) {
        if(method.ownership().runtimeEnabled()) method.ownership().runtimeUnsupported("throw-expression");
        expr.writePushEnv();expr.writePushTraceInfo(token);
        expr.writeExpression(token.getException(),true,false);expr.writePopBoxing();
        expr.writeSysDynamicCall(Environment.class,"__throwException",void.class,TraceInfo.class,Memory.class);
        if(returnValue) expr.writePushNull();
    }
}
