package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.tokenizer.token.stmt.StaticStmtToken;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import php.runtime.Memory;
import php.runtime.env.Environment;

import static org.objectweb.asm.Opcodes.IFNONNULL;

public class StaticDefinitionCompiler extends BaseStatementCompiler<StaticStmtToken> {
    public StaticDefinitionCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    void writePushNameForStaticVariable(LocalVariable local) {
        String name = method.clazz.isClosure()
                ? local.name
                : method.getEntity().getClazz().getInternalName() + "\0" + local.name + "\0" + method.getMethodId();

        if (method.getEntity().getClazz().isTrait()) {
            expr.writePushSelf(false);
            expr.writePushConstString(name);
            expr.writeSysDynamicCall(String.class, "concat", String.class, String.class);
        } else {
            expr.writePushConstString(name);
        }
    }


    @Override
    public void write(StaticStmtToken token) {
        LocalVariable local = method.getLocalVariable(token.getVariable().getName());
        assert local != null;

        LabelNode end = new LabelNode();
        boolean isClosure = method.clazz.isClosure();


        if (isClosure)
            expr.writeVarLoad("~this");
        else
            expr.writePushEnv();

        //writePushConstString(name);
        writePushNameForStaticVariable(local);

        expr.writeSysDynamicCall(isClosure ? null : Environment.class, "getStatic", Memory.class, String.class);
        expr.writePushDup();

        add(new JumpInsnNode(IFNONNULL, end));
        expr.stackPop();

        expr.writePopAll(1);
        if (isClosure)
            expr.writeVarLoad("~this");
        else
            expr.writePushEnv();

        writePushNameForStaticVariable(local);
        //writePushConstString(name);

        if (token.getInitValue() != null){
            expr.writeExpression(token.getInitValue(), true, false, true);
        } else {
            expr.writePushNull();
        }
        expr.writePopBoxing(true);
        expr.writeSysDynamicCall(isClosure ? null : Environment.class, "getOrCreateStatic",
                Memory.class,
                String.class, Memory.class);

        add(end);
        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5 && !method.isExternal()) {
            // A closure static CV borrows the table slot until PHP actually exports a reference.
            // Promoting it here makes ordinary statics share a CELL across closure clones.
            if (!isClosure)
                expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "exportReference", Memory.class, Memory.class);
            if (methodStatement.isDynamicLocal()) {
                expr.writeVarLoad(local);
                expr.writeSysStaticCall(Memory.class, "assignRefRight", Memory.class, Memory.class, Memory.class);
                expr.writePopAll(1);
            } else {
                expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "valueOf", Memory.class, Memory.class);
                expr.writeVarStore(local, false, false);
            }
        } else expr.writeVarStore(local, false, false);
    }
}
