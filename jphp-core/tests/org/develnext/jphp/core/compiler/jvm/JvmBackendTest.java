package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.core.compiler.jvm.node.MethodNodeImpl;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ModuleEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.*;

public class JvmBackendTest extends JvmCompilerCase {
    @Test
    public void testTraitMethodCopiesHaveIndependentInstructionsAndLabels() {
        MethodNode original = new MethodNode(Opcodes.ACC_PUBLIC, "method", "()V", null, null);
        Label start = new Label();
        Label end = new Label();
        original.visitLabel(start);
        original.visitJumpInsn(Opcodes.GOTO, end);
        original.visitLabel(end);
        original.visitInsn(Opcodes.RETURN);
        original.visitLocalVariable("this", "Ljava/lang/Object;", null, start, end, 0);
        original.visitMaxs(0, 1);
        original.visitEnd();

        MethodNodeImpl first = MethodNodeImpl.duplicate(original);
        MethodNodeImpl second = MethodNodeImpl.duplicate(original);
        assertNotSame(original.instructions, first.instructions);
        assertNotSame(first.instructions, second.instructions);
        assertNotSame(first.localVariables, second.localVariables);
        for (int i = 0; i < original.instructions.size(); i++) {
            assertNotSame(original.instructions.get(i), first.instructions.get(i));
            assertNotSame(first.instructions.get(i), second.instructions.get(i));
        }
        for (MethodNode copy : new MethodNode[] {first, second}) {
            for (AbstractInsnNode instruction : copy.instructions) {
                if (instruction instanceof JumpInsnNode) {
                    LabelNode target = ((JumpInsnNode) instruction).label;
                    assertTrue(copy.instructions.contains(target));
                    assertFalse(original.instructions.contains(target));
                }
            }
            assertTrue(copy.instructions.contains(copy.localVariables.get(0).start));
            assertTrue(copy.instructions.contains(copy.localVariables.get(0).end));
        }
    }

    @Test
    public void testControlFlowAndTraitsVerifyBeforeAndAfterDump() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            Context context = new Context(
                    "trait Branching { function calculate($x) { " +
                    "try { if ($x < 0) throw new Exception('negative'); " +
                    "for ($i = 0; $i < 3; $i++) $x += $i; return $x; " +
                    "} catch (Exception $e) { return -1; } finally { $x = 0; } } } " +
                    "class First { use Branching { calculate as alternate; } } " +
                    "class Second { use Branching; } " +
                    "function values() { yield 2; yield 3; } " +
                    "$closure = function($x) { return $x + 1; }; " +
                    "$sum = 0; foreach (values() as $value) $sum += $value; " +
                    "$first = new First; $second = new Second; " +
                    "return $first->calculate(1) + $first->alternate(2) + " +
                    "$second->calculate(-1) + $closure($sum);"
            );
            ModuleEntity module = new JvmCompiler(env, context).compile(false);
            if (dumped) {
                ModuleDumper dumper = new ModuleDumper(context, env, true);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                dumper.save(module, bytes);
                module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            env.getScope().loadModule(module);
            env.getScope().addUserModule(module);
            env.registerModule(module);
            verify(module.getData(), env);
            for (ClassEntity entity : module.getClasses()) verify(entity.getData(), env);
            for (ClassEntity entity : module.getClosures()) verify(entity.getData(), env);
            for (ClassEntity entity : module.getGenerators()) verify(entity.getData(), env);
            for (php.runtime.reflection.FunctionEntity entity : module.getFunctions()) {
                verify(entity.getData(), env);
            }
            try {
                assertEquals(14, module.include(env).toLong());
            } finally {
                env.doFinal();
            }
        }
    }

    @Test
    public void testReadsJava25RuntimeClasses() throws Exception {
        ClassReader reader = new ClassReader(Memory.class.getName());
        assertEquals(Opcodes.V25, reader.readUnsignedShort(6));
        assertEquals("php/runtime/Memory", reader.getClassName());
    }

    private void verify(byte[] bytes, Environment env) {
        assertNotNull(bytes);
        ClassReader reader = new ClassReader(bytes);
        assertEquals(Opcodes.V25, reader.readUnsignedShort(6));
        StringWriter diagnostics = new StringWriter();
        CheckClassAdapter.verify(reader, env.getScope().getClassLoader(), false, new PrintWriter(diagnostics));
        assertEquals(reader.getClassName() + " bytecode diagnostics", "", diagnostics.toString());
    }
}
