package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.stmt.BodyStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.MethodStmtToken;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import php.runtime.Memory;
import php.runtime.PhpModuleContinuation;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import java.util.ArrayList;
import static org.objectweb.asm.Opcodes.*;

/** Split only whole top-level statements, retaining one external local table and PHP call frame. */
final class ModuleBodyCompiler {
    private ModuleBodyCompiler() {}
    static boolean write(ExpressionStmtCompiler expr, MethodStmtToken statement) {
        var method=expr.getMethod(); var body=statement.getBody().getInstructions();
        if (!method.isExternal() || !method.clazz.isSystem() || !statement.getName().getName().equals("__include")
                || body.size() < 128 || statement.getLabels() != null && !statement.getLabels().isEmpty()) return false;
        for (int start=0, index=0; start<body.size(); start+=64,index++) {
            var part=new MethodStmtToken(statement);
            part.setClazz(statement.getClazz()); part.setStatic(true); part.setFinal(true); part.setModuleChunk(true);
            part.setName(NameToken.valueOf("__include_chunk_"+index));
            part.setBody(BodyStmtToken.of(new ArrayList<>(body.subList(start,Math.min(start+64,body.size())))));
            expr.getCompiler().compileMethod(method.clazz,part,true,null);
            expr.writePushEnv(); expr.writeVarLoad("~args"); expr.writePushLocal();
            expr.writeSysCall(method.clazz.node.name,INVOKESTATIC,part.getName().getName(),Memory.class,
                    Environment.class,Memory[].class,ArrayMemory.class);
            expr.writePushDup(); expr.writeSysStaticCall(PhpModuleContinuation.class,"isContinue",boolean.class,Memory.class);
            expr.stackPop();
            var next=new LabelNode(); method.node.instructions.add(new JumpInsnNode(IFNE,next));
            method.node.instructions.add(new InsnNode(ARETURN));
            method.node.instructions.add(next); expr.writePopAll(1);
        }
        return true;
    }
}
