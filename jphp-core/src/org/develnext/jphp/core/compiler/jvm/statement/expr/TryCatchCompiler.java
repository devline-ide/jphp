package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.MethodStmtCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.value.FulledNameToken;
import org.develnext.jphp.core.tokenizer.token.stmt.BodyStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.CatchStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.TryStmtToken;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.lang.BaseException;
import php.runtime.lang.exception.BaseBaseException;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.GOTO;

public class TryCatchCompiler extends BaseStatementCompiler<TryStmtToken> {
    public TryCatchCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    private void writeFinally(BodyStmtToken body) {
        method.beginFinally(body);
        try { expr.write(BodyStmtToken.class, body); }
        finally { method.endFinally(); }
    }

    @Override
    public void write(TryStmtToken token) {
        if (token.getBody() == null || token.getBody().getInstructions().isEmpty()) {
            if (token.getFinally() != null) writeFinally(token.getFinally());
            return;
        }

        expr.writeDefineVariables(token.getLocal());
        LabelNode tryStart = expr.writeLabel(node, token.getMeta().getStartLine());
        LabelNode tryEnd = new LabelNode();
        LabelNode catchStart = new LabelNode();
        LabelNode catchesEnd = new LabelNode();
        LabelNode normalFinally = new LabelNode();
        LabelNode returnFinally = new LabelNode();
        LabelNode exceptionalFinally = new LabelNode();
        LabelNode end = new LabelNode();
        TryCatchBlockNode dispatch = new TryCatchBlockNode(tryStart, tryEnd, catchStart,
                Type.getInternalName(BaseBaseException.class));
        method.node.tryCatchBlocks.add(0, dispatch);

        boolean hasFinally = token.getFinally() != null;
        MethodStmtCompiler.TryCatchItem finallyScope = hasFinally
                ? new MethodStmtCompiler.TryCatchItem(token, returnFinally) : null;
        if (hasFinally) method.getTryStack().push(finallyScope);
        try {
            expr.write(BodyStmtToken.class, token.getBody());
            add(tryEnd);
            add(new JumpInsnNode(GOTO, normalFinally));
            add(catchStart);
            LocalVariable exception = method.addLocalVariable(
                    "~catch~" + method.nextStatementIndex(BaseException.class), catchStart, BaseBaseException.class);
            exception.setEndLabel(catchesEnd);
            expr.makeVarStore(exception);

            for (CatchStmtToken catcher : token.getCatches()) {
                LabelNode nextCatch = new LabelNode();
                LabelNode body = new LabelNode();
                LocalVariable local = method.getLocalVariable(catcher.getVariable().getName());
                List<FulledNameToken> exceptions = catcher.getExceptions();
                for (int i = 0; i < exceptions.size(); i++) {
                    expr.writePushEnv();
                    expr.writeVarLoad(exception);
                    expr.writePushConstString(exceptions.get(i).toName());
                    expr.writePushConstString(exceptions.get(i).toName().toLowerCase());
                    expr.writeSysDynamicCall(Environment.class, "__throwCatch", Memory.class,
                            BaseBaseException.class, String.class, String.class);
                    expr.writeVarAssign(local, catcher.getVariable(), true, false);
                    expr.writePopBoolean();
                    LabelNode nextType = i == exceptions.size() - 1 ? nextCatch : new LabelNode();
                    add(new JumpInsnNode(IFEQ, nextType));
                    expr.stackPop();
                    add(new JumpInsnNode(GOTO, body));
                    if (nextType != nextCatch) add(nextType);
                }
                add(body);
                // Catch returns share the pending-return route; sibling catches never handle this body.
                expr.write(BodyStmtToken.class, catcher.getBody());
                add(new JumpInsnNode(GOTO, normalFinally));
                add(nextCatch);
            }
            expr.makeVarLoad(exception);
            add(new InsnNode(ATHROW));
            add(catchesEnd);
        } finally {
            if (hasFinally) method.getTryStack().pop();
        }

        if (hasFinally) {
            // Nested handlers keep precedence; this cleanup precedes every enclosing handler.
            int dispatchIndex = method.node.tryCatchBlocks.indexOf(dispatch);
            method.node.tryCatchBlocks.add(dispatchIndex + 1, new TryCatchBlockNode(
                    tryStart, catchesEnd, exceptionalFinally, Type.getInternalName(BaseBaseException.class)));
            add(exceptionalFinally);
            LocalVariable pending = method.addLocalVariable(
                    "~finallyPending~" + method.nextStatementIndex(BaseBaseException.class), exceptionalFinally,
                    BaseBaseException.class);
            expr.makeVarStore(pending);
            LabelNode finallyStart = new LabelNode();
            LabelNode finallyEnd = new LabelNode();
            LabelNode chainFailure = new LabelNode();
            add(finallyStart);
            writeFinally(token.getFinally());
            add(finallyEnd);
            expr.makeVarLoad(pending);
            add(new InsnNode(ATHROW));

            boolean executable = false;
            for (var instruction = finallyStart.getNext(); instruction != finallyEnd; instruction = instruction.getNext()) {
                if (instruction.getOpcode() >= 0) { executable = true; break; }
            }
            if (executable) {
                method.node.tryCatchBlocks.add(method.node.tryCatchBlocks.indexOf(dispatch),
                        new TryCatchBlockNode(finallyStart, finallyEnd, chainFailure,
                        Type.getInternalName(BaseBaseException.class)));
                add(chainFailure);
                LocalVariable replacement = method.addLocalVariable(
                        "~finallyReplacement~" + method.nextStatementIndex(BaseBaseException.class), chainFailure,
                        BaseBaseException.class);
                expr.makeVarStore(replacement);
                expr.writeVarLoad(replacement);
                expr.writeVarLoad(pending);
                expr.writeSysStaticCall(php.runtime.invoke.InvokeHelper.class, "chainFinallyException", BaseBaseException.class,
                        BaseBaseException.class, BaseBaseException.class);
                expr.stackPop();
                add(new InsnNode(ATHROW));
            }
        }

        add(normalFinally);
        if (hasFinally) writeFinally(token.getFinally());
        add(new JumpInsnNode(GOTO, end));
        if (hasFinally) {
            add(returnFinally);
            writeFinally(token.getFinally());
            if (method.getTryStack().empty()) {
                expr.writeVarLoad(method.getOrAddLocalVariable("~result~", null, Memory.class));
                add(new InsnNode(ARETURN));
                expr.stackPop();
            } else {
                add(new JumpInsnNode(GOTO, method.getTryStack().peek().getReturnLabel()));
            }
        }
        if (hasFinally) {
            for (var route : finallyScope.getExitLabels().entrySet()) {
                add(route.getValue());
                writeFinally(token.getFinally());
                add(new JumpInsnNode(GOTO, route.getKey()));
            }
        }
        add(end);
        expr.writeUndefineVariables(token.getLocal());
        method.prevStatementIndex(BaseBaseException.class);
    }

}
