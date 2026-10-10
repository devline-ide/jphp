package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.misc.JumpItem;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.tokenizer.token.stmt.BreakStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ContinueStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.JumpStmtToken;
import org.objectweb.asm.tree.JumpInsnNode;
import php.runtime.common.Messages;
import php.runtime.exceptions.support.ErrorType;

import static org.objectweb.asm.Opcodes.GOTO;

public class JumpCompiler extends BaseStatementCompiler<JumpStmtToken> {
    public JumpCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    @Override
    public void write(JumpStmtToken token) {
        int level = token.getLevel();
        JumpItem jump = method.getJump(level);

        if (jump == null){
            env.error(
                    token.toTraceInfo(compiler.getContext()),
                    ErrorType.E_COMPILE_ERROR,
                    level == 1
                            ? Messages.ERR_CANNOT_JUMP.fetch()
                            : Messages.ERR_CANNOT_JUMP_TO_LEVEL.fetch(level)
            );
            return;
        }

        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && method.isLeavingFinally(jump)) {
            env.error(token.toTraceInfo(compiler.getContext()), ErrorType.E_COMPILE_ERROR,
                    "jump out of a finally block is disallowed");
            return;
        }
        if (token instanceof ContinueStmtToken){
            add(new JumpInsnNode(GOTO, method.routeFinallyExit(jump.continueLabel, jump.tryScopes)));
        } else if (token instanceof BreakStmtToken){
            add(new JumpInsnNode(GOTO, method.routeFinallyExit(jump.breakLabel, jump.tryScopes)));
        }
    }
}
