package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.tokenizer.token.stmt.EchoStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import php.runtime.Memory;
import php.runtime.env.Environment;

public class EchoCompiler extends BaseStatementCompiler<EchoStmtToken> {
    public EchoCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    @Override
    public void write(EchoStmtToken token) {
        for(ExprStmtToken argument : token.getArguments()){
            expr.writePushEnv();
            expr.writeExpression(argument, true, false);
            int runtimeEval=expr.stackPeek().runtimeEvaluationId;
            if (method.ownership().enabled() && argument.isSingle()
                    && argument.getSingle() instanceof org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken literal
                    && literal.getSegments().isEmpty() && !literal.isBinary() && !literal.hasRawBytes()) {
                expr.writePopString(); method.ownership().literalEcho(expr.getMethodNode().instructions);
                expr.stackPop(); expr.stackPop(); expr.releaseRuntimeEvaluation(runtimeEval,"POP"); continue;
            }
            expr.writePopBoxing();
            if (method.ownership().runtimeEnabled()) {
                var code=expr.getMethodNode().instructions;
                var fallback=new org.objectweb.asm.tree.LabelNode();var end=new org.objectweb.asm.tree.LabelNode();
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD,method.ownership().runtimeFrameIndex()));
                code.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.IFNULL,fallback));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD,method.ownership().runtimeFrameIndex()));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP_X2));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.POP));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.SWAP));
                var call=new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                        "php/runtime/PhpOwnershipNativeCalls","echoRuntime",
                        "(Lphp/runtime/PhpRuntimeOwnership$Frame;Lphp/runtime/Memory;Lphp/runtime/env/Environment;)V",false);
                code.add(call);method.ownership().runtimeEcho(call);
                expr.stackPop();expr.stackPop();
                code.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.GOTO,end));code.add(fallback);
                // The guarded null-Frame lane still has the original Environment/Memory operands.
                expr.stackPush(Memory.Type.REFERENCE);expr.stackPush(Memory.Type.REFERENCE);
                expr.writePushTraceInfo(argument);
                expr.writeSysStaticCall(php.runtime.output.OutputBuffer.class,"echoPhp85",void.class,
                        Environment.class,Memory.class,php.runtime.env.TraceInfo.class);
                code.add(end);method.ownership().runtimeFallback(fallback,end);
                expr.releaseRuntimeEvaluation(runtimeEval,"POP");
            } else if (compiler.getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                expr.writePushTraceInfo(argument);
                expr.writeSysStaticCall(php.runtime.output.OutputBuffer.class,"echoPhp85",void.class,
                        Environment.class,Memory.class,php.runtime.env.TraceInfo.class);
            } else expr.writeSysDynamicCall(Environment.class, "echo", void.class, Memory.class);
        }
    }
}
