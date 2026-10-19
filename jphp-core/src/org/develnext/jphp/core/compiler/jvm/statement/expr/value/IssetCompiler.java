package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.value.IssetExprToken;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import php.runtime.Memory;
import php.runtime.OperatorUtils;

public class IssetCompiler extends BaseExprCompiler<IssetExprToken> {
    public IssetCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    @Override
    public void write(IssetExprToken token, boolean returnValue) {
        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            LabelNode missing = new LabelNode(), end = new LabelNode();
            for (var parameter : token.getParameters()) {
                expr.writeQuietly(java.util.Collections.singleton(parameter),
                        () -> expr.writeExpression(parameter, true, false));
                expr.writePopBoxing();
                expr.writeSysDynamicCall(Memory.class, "isNull", boolean.class);
                add(new JumpInsnNode(Opcodes.IFNE, missing));
                expr.stackPop();
            }
            expr.writePushConstBoolean(true);
            add(new JumpInsnNode(Opcodes.GOTO, end));
            expr.stackPop();
            add(missing);
            expr.writePushConstBoolean(false);
            add(end);
            if (!returnValue) expr.writePopAll(1);
            return;
        }
        expr.writeQuietly(token.getParameters(), () -> expr.writePushParameters(token.getParameters()));
        expr.writeSysStaticCall(OperatorUtils.class, "isset", Boolean.TYPE, Memory[].class);

        if (!returnValue)
            expr.writePopAll(1);
    }
}
