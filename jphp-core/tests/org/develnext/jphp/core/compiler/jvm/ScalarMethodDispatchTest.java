package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import php.runtime.Memory;
import php.runtime.PhpOwnershipDomain;
import php.runtime.PhpOwnershipExecution;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.loader.dump.ModuleDumper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/** Exercises cached optional dispatch independently from scalar body pattern matching. */
public class ScalarMethodDispatchTest extends JvmCompilerCase {
    @Test public void actualSourceAndPhbPhpCallsSelectTheOrdinaryEntry() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            var env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                var fixture = fixture(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
                var entity = entity(fixture, "original");
                assertFalse("Unmarked extension entry must retain argument isolation", entity.acceptsBorrowedScalarArguments());
                entity.getClazz().addMethod(entity, null).check(env);
                env.getScope().registerClass(entity.getClazz());
                var context = new Context("return DispatchFixture::original();");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                assertEquals(42, module.include(env).toLong());
                assertEquals(1, fixture.getField("entryCalls").getInt(null));
                assertEquals("original", entity.getNativeMethod().getName());
            } finally { env.doFinal(); }
        }
    }

    @Test public void optionalEntryIsCachedWithoutReplacingTheAdmittedMethodIdentity() throws Throwable {
        var env = new Environment(newScope());
        try {
            Class<?> fixture = fixture(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
            var entity = entity(fixture, "original"); Method original = entity.getNativeMethod();
            assertEquals(42, entity.invokeStatic(env).toLong());
            assertSame(original, entity.getNativeMethod());
            assertEquals(1, fixture.getField("entryCalls").getInt(null));
            entity.setNativeMethod(fixture.getDeclaredMethod("plain", Environment.class, Memory[].class));
            assertEquals(99, entity.invokeStatic(env).toLong());
            assertEquals(1, fixture.getField("entryCalls").getInt(null));
            entity.setNativeMethod(original);
            assertEquals(42, entity.invokeStatic(env).toLong());
            assertEquals(2, fixture.getField("entryCalls").getInt(null));
        } finally { env.doFinal(); }
    }

    @Test public void unmanagedLookalikesAreNotCompilerEntries() throws Throwable {
        for (int flags : new int[] {Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC}) {
            var env = new Environment(newScope());
            try {
                var fixture = fixture(flags); var entity = entity(fixture, "original");
                assertEquals(41, entity.invokeStatic(env).toLong());
                assertEquals(0, fixture.getField("entryCalls").getInt(null));
            } finally { env.doFinal(); }
        }
    }

    @Test public void ordinaryOwnershipAuthorityKeepsTheOriginalEntry() throws Throwable {
        var env = new Environment(newScope());
        try {
            var fixture = fixture(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
            var entity = entity(fixture, "original");
            try (var authority = new PhpRuntimeOwnership.Authority(env, new PhpOwnershipDomain()); var execution = authority.enter()) {
                assertEquals(41, entity.invokeStatic(env).toLong());
                assertEquals(0, fixture.getField("entryCalls").getInt(null));
                assertEquals("original", entity.getNativeMethod().getName());
            }
        } finally { env.doFinal(); }
    }

    @Test public void explicitOwnershipSessionCannotBypassAdmissionThroughTheEntry() throws Throwable {
        var env = new Environment(newScope());
        try {
            var fixture = fixture(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
            var entity = entity(fixture, "original"); var domain = new PhpOwnershipDomain();
            var participant = domain.participant(env); var session = new PhpOwnershipExecution.Session(participant, env);
            try (var execution = session.enter()) {
                // The hand-built fixture has no admitted ownership plan, so invocation must fail closed.
                assertThrows(Throwable.class, () -> entity.invokeStatic(env));
                assertEquals(0, fixture.getField("entryCalls").getInt(null));
                assertEquals("original", entity.getNativeMethod().getName());
            } finally {
                try (var execution = participant.enter()) { participant.close(); }
            }
        } finally { env.doFinal(); }
    }

    private MethodEntity entity(Class<?> fixture, String name) throws Exception {
        var clazz = new ClassEntity(new Context("")); clazz.setName("DispatchFixture");
        var entity = new MethodEntity(new Context("")); entity.setName(name); entity.setInternalName(name);
        entity.setClazz(clazz); entity.setStatic(true); entity.setParameters(new php.runtime.reflection.ParameterEntity[0]);
        entity.setModifier(php.runtime.common.Modifier.PUBLIC);
        entity.setNativeMethod(fixture.getDeclaredMethod(name, Environment.class, Memory[].class)); return entity;
    }

    private Class<?> fixture(int entryFlags) {
        String owner = "scalar/DispatchFixture", signature = "(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "entryCalls", "I", null, null).visitEnd();
        constantMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "original", signature, 41);
        constantMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "plain", signature, 99);
        MethodVisitor entry = writer.visitMethod(entryFlags, MethodEntity.SCALAR_ENTRY_PREFIX + "original", signature, null, null);
        entry.visitCode(); entry.visitFieldInsn(Opcodes.GETSTATIC, owner, "entryCalls", "I"); entry.visitInsn(Opcodes.ICONST_1);
        entry.visitInsn(Opcodes.IADD); entry.visitFieldInsn(Opcodes.PUTSTATIC, owner, "entryCalls", "I");
        entry.visitLdcInsn(42L); entry.visitMethodInsn(Opcodes.INVOKESTATIC, "php/runtime/memory/LongMemory", "valueOf", "(J)Lphp/runtime/Memory;", false);
        entry.visitInsn(Opcodes.ARETURN); entry.visitMaxs(0, 0); entry.visitEnd(); writer.visitEnd();
        return new ClassLoader(MethodEntity.class.getClassLoader()) {
            Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        }.define(writer.toByteArray());
    }

    private void constantMethod(ClassWriter writer, int flags, String name, String signature, long value) {
        MethodVisitor method = writer.visitMethod(flags, name, signature, null, null); method.visitCode();
        method.visitLdcInsn(value); method.visitMethodInsn(Opcodes.INVOKESTATIC, "php/runtime/memory/LongMemory", "valueOf", "(J)Lphp/runtime/Memory;", false);
        method.visitInsn(Opcodes.ARETURN); method.visitMaxs(0, 0); method.visitEnd();
    }
}
