package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import static org.junit.Assert.assertEquals;

public class LocalSlotAllocatorTest {
    private MethodStmtCompiler method() throws Exception {
        var compiler = new JvmCompiler(new Environment(), new Context(""));
        var owner = new ClassStmtCompiler(compiler, null);
        var node = new MethodNode();
        node.name = "slots";
        return new MethodStmtCompiler(owner, node);
    }

    @Test public void replacingLexicalNamesDoesNotReuseEmittedSlots() throws Exception {
        var method = method();
        assertEquals(0, method.addLocalVariable("temporary", null, Object.class).index);
        assertEquals(1, method.addLocalVariable("temporary", null, Object.class).index);
        assertEquals(2, method.addLocalVariable("context", null, Object.class).index);
    }

    @Test public void wideLocalsReserveBothJvmSlots() throws Exception {
        var method = method();
        assertEquals(0, method.addLocalVariable("long", null, long.class).index);
        assertEquals(2, method.addLocalVariable("double", null, double.class).index);
        assertEquals(4, method.addLocalVariable("context", null, Object.class).index);
    }

    @Test public void lateInstrumentationReservesRawWideAndIncrementSlots() throws Exception {
        var method = method();
        method.node.instructions.add(new VarInsnNode(Opcodes.LSTORE, 6));
        method.node.instructions.add(new VarInsnNode(Opcodes.DLOAD, 12));
        method.reserveEmittedLocals();
        assertEquals(14, method.addLocalVariable("context", null, Object.class).index);
        method.node.instructions.add(new IincInsnNode(20, 1));
        method.reserveEmittedLocals();
        assertEquals(21, method.addLocalVariable("error", null, Throwable.class).index);
        method.node.maxLocals = 30;
        method.reserveEmittedLocals();
        assertEquals(30, method.addLocalVariable("extra", null, Object.class).index);
    }
}
