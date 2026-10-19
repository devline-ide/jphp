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
                ? method.newTryCatchItem(token, returnFinally) : null;
        if (hasFinally) method.getTryStack().push(finallyScope);
        try {
            expr.write(BodyStmtToken.class, token.getBody());
            add(tryEnd);
            add(new JumpInsnNode(GOTO, normalFinally));
            add(catchStart);
            int dispatchCustody = method.ownership().enabled() ? method.ownership().thrownCustody() : -1;
            method.ownership().captureThrown(node.instructions, dispatchCustody);
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
                    if (!method.ownership().matchThrown(expr, dispatchCustody, exception, exceptions.get(i).toName())) {
                        expr.writePushEnv(); expr.writeVarLoad(exception);
                        expr.writePushConstString(exceptions.get(i).toName()); expr.writePushConstString(exceptions.get(i).toName().toLowerCase());
                        expr.writeSysDynamicCall(Environment.class, "__throwCatch", Memory.class, BaseBaseException.class, String.class, String.class);
                    }
                    boolean modern = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
                    LocalVariable matched = null;
                    if (modern) {
                        matched = method.addLocalVariable("~catchMatch~" + method.nextStatementIndex(CatchStmtToken.class), null, Memory.class);
                        expr.makeVarStore(matched); expr.stackPop();
                        expr.writeVarLoad(matched);
                    } else {
                        expr.writeVarAssign(local, catcher.getVariable(), true, false);
                    }
                    expr.writePopBoolean();
                    LabelNode nextType = i == exceptions.size() - 1 ? nextCatch : new LabelNode();
                    add(new JumpInsnNode(IFEQ, nextType));
                    expr.stackPop();
                    if (modern) {
                        expr.writeVarLoad(matched);
                        if (!method.ownership().assignCatch(expr, dispatchCustody, local, catcher.getVariable()))
                            expr.writeVarAssign(local, catcher.getVariable(), false, false);
                    }
                    add(new JumpInsnNode(GOTO, body));
                    if (nextType != nextCatch) add(nextType);
                }
                add(body);
                // Catch returns share the pending-return route; sibling catches never handle this body.
                expr.write(BodyStmtToken.class, catcher.getBody());
                add(new JumpInsnNode(GOTO, normalFinally));
                add(nextCatch);
            }
            if (!method.ownership().rethrowThrown(node.instructions, dispatchCustody, exception)) expr.makeVarLoad(exception);
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
            int pendingCustody = method.ownership().enabled() ? method.ownership().thrownCustody() : -1;
            method.ownership().captureThrown(node.instructions, pendingCustody);
            LocalVariable pending = method.addLocalVariable(
                    "~finallyPending~" + method.nextStatementIndex(BaseBaseException.class), exceptionalFinally,
                    BaseBaseException.class);
            expr.makeVarStore(pending);
            method.ownership().abortReturn(node.instructions, finallyScope.getReturnCustodyId());
            LabelNode finallyStart = new LabelNode();
            LabelNode finallyEnd = new LabelNode();
            LabelNode chainFailure = new LabelNode();
            add(finallyStart);
            method.ownership().beginThrownFinally(token.getFinally(), pendingCustody);
            try { writeFinally(token.getFinally()); } finally { method.ownership().endThrownFinally(); }
            add(finallyEnd);
            if (!method.ownership().rethrowThrown(node.instructions, pendingCustody, pending)) expr.makeVarLoad(pending);
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
                int replacementCustody = method.ownership().enabled() ? method.ownership().thrownCustody() : -1;
                method.ownership().captureThrown(node.instructions, replacementCustody);
                LocalVariable replacement = method.addLocalVariable(
                        "~finallyReplacement~" + method.nextStatementIndex(BaseBaseException.class), chainFailure,
                        BaseBaseException.class);
                expr.makeVarStore(replacement);
                method.ownership().abortReturn(node.instructions, finallyScope.getReturnCustodyId());
                expr.writeVarLoad(replacement);
                expr.writeVarLoad(pending);
                if (method.ownership().chainThrown(expr)) {
                    expr.stackPop(); add(new InsnNode(POP)); method.ownership().releaseThrown(node.instructions, pendingCustody);
                    method.ownership().rethrowThrown(node.instructions, replacementCustody, replacement);
                } else {
                    expr.writeSysStaticCall(php.runtime.invoke.InvokeHelper.class, "chainFinallyException", BaseBaseException.class, BaseBaseException.class, BaseBaseException.class);
                    expr.stackPop();
                }
                add(new InsnNode(ATHROW));
            }
        }

        if (hasFinally) {
            // Typed native cancellation bypasses PHP catches; exit and other JVM failures keep their own semantics.
            LabelNode nativeFinally = new LabelNode();
            int dispatchIndex = method.node.tryCatchBlocks.indexOf(dispatch);
            method.node.tryCatchBlocks.add(dispatchIndex + 2, new TryCatchBlockNode(
                    tryStart, catchesEnd, nativeFinally, Type.getInternalName(php.runtime.exceptions.PhpUnwindSignal.class)));
            add(nativeFinally);
            LocalVariable pending = method.addLocalVariable(
                    "~nativeFinally~" + method.nextStatementIndex(php.runtime.exceptions.PhpUnwindSignal.class), nativeFinally, php.runtime.exceptions.PhpUnwindSignal.class);
            expr.makeVarStore(pending);
            method.ownership().abortReturn(node.instructions, finallyScope.getReturnCustodyId());
            writeFinally(token.getFinally());
            expr.makeVarLoad(pending);
            add(new InsnNode(ATHROW));
        }

        add(normalFinally);
        if (hasFinally) writeFinally(token.getFinally());
        add(new JumpInsnNode(GOTO, end));
        if (hasFinally) {
            add(returnFinally);
            // A failed return-finally clone must discard only its own pending candidate.
            // Its handlers precede enclosing dispatch, but follow handlers nested in the clone.
            LabelNode returnCleanupStart = new LabelNode(), returnCleanupEnd = new LabelNode();
            LabelNode returnCleanupFailure = new LabelNode(), returnCleanupDone = new LabelNode();
            add(returnCleanupStart);
            writeFinally(token.getFinally());
            add(returnCleanupEnd);
            add(new JumpInsnNode(GOTO, returnCleanupDone));
            boolean returnCleanupExecutable = false;
            for (var instruction = returnCleanupStart.getNext(); instruction != returnCleanupEnd; instruction = instruction.getNext()) {
                if (instruction.getOpcode() >= 0) { returnCleanupExecutable = true; break; }
            }
            if (method.ownership().enabled() && returnCleanupExecutable) {
                method.node.tryCatchBlocks.add(method.node.tryCatchBlocks.indexOf(dispatch),
                        new TryCatchBlockNode(returnCleanupStart, returnCleanupEnd, returnCleanupFailure,
                                Type.getInternalName(BaseBaseException.class)));
                add(returnCleanupFailure);
                int failedReturnCustody = method.ownership().thrownCustody();
                method.ownership().captureThrown(node.instructions, failedReturnCustody);
                LocalVariable failedReturn = method.addLocalVariable(
                        "~failedReturnFinally~" + method.nextStatementIndex(BaseBaseException.class),
                        returnCleanupFailure, BaseBaseException.class);
                expr.makeVarStore(failedReturn);
                method.ownership().abortReturn(node.instructions, finallyScope.getReturnCustodyId());
                if (!method.ownership().rethrowThrown(node.instructions, failedReturnCustody, failedReturn)) expr.makeVarLoad(failedReturn);
                add(new InsnNode(ATHROW));
            }
            add(returnCleanupDone);
            if (method.getTryStack().empty()) {
                method.ownership().leaveThrownScopes(node.instructions, List.of());
                expr.writeVarLoad(method.getOrAddLocalVariable(finallyScope.getReturnVariable(), null, Memory.class));
                add(new InsnNode(ARETURN));
                expr.stackPop();
            } else {
                MethodStmtCompiler.TryCatchItem outer = method.getTryStack().peek();
                if (!outer.getReturnVariable().equals(finallyScope.getReturnVariable())) {
                    expr.writeVarLoad(method.getOrAddLocalVariable(finallyScope.getReturnVariable(), null, Memory.class));
                    LocalVariable destination = method.getOrAddLocalVariable(outer.getReturnVariable(), null, Memory.class);
                    if (method.ownership().forwardReturn(node.instructions, finallyScope.getReturnCustodyId(), outer.getReturnCustodyId(), destination)) expr.stackPop();
                    else expr.writeVarStore(destination, false, false);
                }
                method.ownership().leaveThrownScopes(node.instructions, outer.getFinallyBodies());
                add(new JumpInsnNode(GOTO, outer.getReturnLabel()));
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
