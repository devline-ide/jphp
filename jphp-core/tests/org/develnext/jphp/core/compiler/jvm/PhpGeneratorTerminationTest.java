package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.lang.Generator;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ModuleEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class PhpGeneratorTerminationTest extends JvmCompilerCase {
    private Generator create(Environment env, String source, boolean debug, boolean dumped) throws Throwable {
        Context context = new Context(source);
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        env.getScope().setDebugMode(debug);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        Memory result = module.include(env);
        return (Generator) result.toValue(ObjectMemory.class).value;
    }

    private static Thread worker(Generator generator) throws Exception {
        Field field = Generator.class.getDeclaredField("iterator"); field.setAccessible(true);
        Object iterator = field.get(generator);
        for (Field captured : iterator.getClass().getDeclaredFields()) {
            if (!captured.getType().getName().endsWith("ThreadedYieldAdapter$VarContainer")) continue;
            captured.setAccessible(true);
            Object holder = captured.get(iterator);
            Field value = holder.getClass().getDeclaredField("var"); value.setAccessible(true);
            return (Thread) value.get(holder);
        }
        throw new AssertionError("actual generator worker not found");
    }

    @Test(timeout = 8000)
    public void environmentFinalizationRetiresNeverStartedWorkerWithoutRunningBody() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(newScope(), output);
            Generator generator = create(env, "return (function() { echo 'started'; try { yield 1; } finally { echo 'cleaned'; } })();", debug, dumped);
            Thread worker = worker(generator);
            try {
                assertTrue(worker.isAlive());
                env.doFinal();
                assertFalse("unstarted worker survives Environment.doFinal", worker.isAlive());
                assertEquals("", output.toString(StandardCharsets.UTF_8));
            } finally {
                generator.__destruct(env);
                env.doFinal();
            }
        }
    }

    @Test(timeout = 8000)
    public void environmentFinalizationCompletesSuspendedFinallyAndRetiresWorker() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(newScope(), output);
            Generator generator = create(env, "return (function() { try { yield 1; } finally { echo \"cleaned\\n\"; } })();", debug, dumped);
            Thread worker = worker(generator);
            try {
                generator.rewind(env);
                assertTrue(worker.isAlive());
                env.doFinal();
                assertFalse("suspended worker survives cleanup", worker.isAlive());
                assertEquals("cleaned\n", output.toString(StandardCharsets.UTF_8));
            } finally {
                generator.__destruct(env);
                env.doFinal();
            }
        }
    }

    @Test(timeout = 8000)
    public void throwingFinallyRetiresWorkerBeforeFailureReachesCaller() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            Generator generator = create(env, "return (function() { try { yield 1; } finally { throw new Exception('close-failure'); } })();", debug, dumped);
            Thread worker = worker(generator);
            try {
                generator.rewind(env);
                try { generator.__destruct(env); fail("throwing finally suppressed"); }
                catch (RuntimeException expected) {
                    assertTrue(expected.toString(), expected.toString().contains("close-failure"));
                    assertFalse("worker remains alive after throwing cleanup", worker.isAlive());
                }
            } finally {
                generator.__destruct(env);
                env.doFinal();
            }
        }
    }
}
