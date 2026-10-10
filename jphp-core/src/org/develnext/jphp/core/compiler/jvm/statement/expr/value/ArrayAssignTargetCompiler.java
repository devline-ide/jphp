package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.value.ArrayAssignTargetExprToken;

public class ArrayAssignTargetCompiler extends BaseExprCompiler<ArrayAssignTargetExprToken> {
    public ArrayAssignTargetCompiler(ExpressionStmtCompiler expr) { super(expr); }

    @Override public void write(ArrayAssignTargetExprToken token, boolean returnValue) {
        new NullCoalesceAssignCompiler(expr).writeTarget(token.getTarget(), token, true);
        expr.stackPeek().capturedArrayAssignment = true;
    }
}
