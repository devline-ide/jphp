package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.tokenizer.token.stmt.ReturnStmtToken;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import php.runtime.Memory;
import php.runtime.common.Function;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.StringUtils;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.InvokeHelper;
import php.runtime.lang.Generator;
import php.runtime.reflection.support.TypeChecker;

import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.GOTO;

public class ReturnCompiler extends BaseStatementCompiler<ReturnStmtToken> {
    public ReturnCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    @Override
    public void write(ReturnStmtToken token) {
        if(token.getValue()!=null)for(var value:token.getValue().getTokens())
            if(value instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.VoidCastExprToken)
                throw new php.runtime.exceptions.ParseException("syntax error, unexpected token \"(void)\", expecting \";\"",value.toTraceInfo(compiler.getContext()));
        if (token.isImplicit() && methodStatement.isModuleChunk()) {
            expr.writeGetStatic(php.runtime.PhpModuleContinuation.class, "CONTINUE", Memory.class);
            add(new InsnNode(ARETURN)); expr.stackPop(); return;
        }
        boolean isGeneratorReturn = false;
        if (methodStatement.isReturnReference() && compiler.getModule().getPhpLanguageProfile()==PhpLanguageProfile.PHP_8_5
                && org.develnext.jphp.core.compiler.common.NullsafeChain.isResult(token.getValue())
                && !(token.getValue().getAsmExpr().getLast() instanceof org.develnext.jphp.core.tokenizer.token.expr.value.CallExprToken))
            env.error(token.toTraceInfo(compiler.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Cannot take reference of a nullsafe chain");

        if (!token.isImplicit() && methodStatement.getReturnHintType() == HintType.NEVER) {
            env.error(token.toTraceInfo(compiler.getContext()), ErrorType.E_COMPILE_ERROR,
                    "A never-returning function must not return");
        }

        if (!token.isImplicit() && token.getValue() == null && method.getGeneratorEntity() == null
                && compiler.getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5
                && method.getEntity().getReturnTypeChecker() != null
                && methodStatement.getReturnHintType() != HintType.VOID) {
            env.error(token.toTraceInfo(compiler.getContext()), ErrorType.E_COMPILE_ERROR,
                    "A function with return type must return a value");
        }

        if (token.getValue() != null && method.getGeneratorEntity() != null) {
            isGeneratorReturn = true;

            expr.writeVarLoad("~this");
            expr.writePushEnv();
            expr.writePushTraceInfo(token);

            /*env.error(
                    token.toTraceInfo(compiler.getContext()),
                    ErrorType.E_ERROR,
                    "Generators cannot return values using \"return\""
            );*/
        }

        Memory result = token.isEmpty() ? Memory.UNDEFINED : Memory.NULL;

        boolean isImmutable = method.getEntity().isImmutable();

        if (token.getValue() != null) {
            result = expr.writeExpression(token.getValue(), true, true);

            if (methodStatement.getReturnHintType() == HintType.VOID) {
                String suffix = result != null && result.isNull() ?
                        " (did you mean \"return;\" instead of \"return null;\"?)" : "";

                env.error(
                        token.toTraceInfo(compiler.getContext()),
                        ErrorType.E_ERROR,
                        "A void function must not return a value" + suffix
                );
            }
        }

        if (result != null) {
            if (methodStatement.getReturnHintType() == HintType.VOID) {
                if (methodStatement.isReturnOptional()) {
                    env.error(
                            token.toTraceInfo(compiler.getContext()),
                            ErrorType.E_ERROR,
                            "Void type cannot be nullable"
                    );
                }
            }

            if (isImmutable) {
                Memory r = method.getEntity().getResult();
                if (r == null || r.isUndefined()) {
                    method.getEntity().setResult(result);
                }
            }
            expr.writePushMemory(result);
        } else {
            method.getEntity().setImmutable(false);
        }

        if (expr.stackEmpty(false)) {
            expr.writePushNull();
        } else {
            expr.writePopBoxing(false);
        }

        if (token.getValue() != null && method.getEntity().getReturnTypeChecker() != null
                && compiler.getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5) {
            if (!method.ownership().normalizeReturn(expr.getMethodNode().instructions))
                expr.writeSysStaticCall(InvokeHelper.class, "normalizeExplicitReturn", Memory.class, Memory.class);
        }

        if (method.getEntity().isReturnReference()) {
            boolean modern = compiler.getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5;
            if (!modern) expr.writePushDup();
            boolean runtimeReference = modern && method.ownership().runtimeEnabled();
            if (runtimeReference) {
                add(new VarInsnNode(org.objectweb.asm.Opcodes.ALOAD,method.ownership().runtimeFrameIndex()));
                expr.stackPush(Memory.Type.REFERENCE);add(new InsnNode(org.objectweb.asm.Opcodes.SWAP));
            }
            expr.writePushEnv();
            expr.writePushTraceInfo(token);
            if (runtimeReference) expr.writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"prepareReturnReference",Memory.class,
                    php.runtime.PhpRuntimeOwnership.Frame.class,Memory.class,Environment.class,TraceInfo.class);
            else expr.writeSysStaticCall(
                    InvokeHelper.class,
                    modern ? "prepareReturnReference" : "checkReturnReference",
                    modern ? Memory.class : void.class,
                    Memory.class, Environment.class, TraceInfo.class
            );
        } else {
            expr.writePopImmutable();
        }

        if (!isGeneratorReturn) {
            int evalId = expr.stackPeek().ownershipEvalId;
            if (compiler.getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5
                    && method.getEntity().getReturnTypeChecker() != null
                    && (!method.getTryStack().empty() || method.isCompilingFinally()))
                method.ownership().verifyReturnBeforeFinally(expr, token, evalId);
            int returnId = method.getTryStack().empty() ? Integer.MAX_VALUE - 1 : method.getTryStack().peek().getReturnCustodyId();
            method.ownership().pendingReturn(expr.getMethodNode().instructions, method.getEntity().isReturnReference(), evalId, returnId); expr.stackPeek().ownershipEvalId = -1;
            method.ownership().runtimePendingReturn(expr.getMethodNode().instructions,method.getEntity().isReturnReference(),expr.stackPeek().runtimeEvaluationId);
            expr.stackPeek().runtimeEvaluationId=-1;
        }

        if (isGeneratorReturn) {
            expr.writeSysDynamicCall(Generator.class, "setReturn", Memory.class, Environment.class, TraceInfo.class, Memory.class);
        }

        if (!method.getTryStack().empty()) {
            String pending = method.getTryStack().peek().getReturnVariable();
            LocalVariable variable = method.getLocalVariable(pending);
            if (variable == null) {
                variable = method.addLocalVariable(pending, null, Memory.class);
            }

            expr.writeVarStore(variable, false, false);
            add(new JumpInsnNode(GOTO, method.getTryStack().peek().getReturnLabel()));
        } else {
            method.ownership().leaveThrownScopes(expr.getMethodNode().instructions, java.util.List.of());
            add(new InsnNode(ARETURN));
            //removeStackFrame();
            expr.stackPop();
        }
    }
}
