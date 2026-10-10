package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.DieException;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.support.Entity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Native PHP abrupt-completion order, pending returns and Throwable previous chains. */
public class PhpTryFinallyTest extends JvmCompilerCase {
    private String resource(String name, String suffix) throws IOException {
        String path = "resources/type_hinting/" + name + "." + suffix;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ModuleEntity compile(Environment env, String source, boolean dumped) throws Throwable {
        Context context = new Context(source);
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module);
        env.getScope().addUserModule(module);
        env.registerModule(module);
        ArrayList<Entity> entities = new ArrayList<>();
        entities.add(module);
        entities.addAll(module.getClasses());
        entities.addAll(module.getFunctions());
        entities.addAll(module.getClosures());
        entities.addAll(module.getGenerators());
        StringWriter diagnostics = new StringWriter();
        PrintWriter writer = new PrintWriter(diagnostics);
        for (Entity entity : entities) if (entity.getData() != null && entity.getData().length != 0) {
            CheckClassAdapter.verify(new ClassReader(entity.getData()), env.getScope().getClassLoader(), false, writer);
        }
        assertEquals("ASM dump=" + dumped, "", diagnostics.toString());
        return module;
    }

    private void checkNative(String fixture, int rows) throws Throwable {
        String source = resource(fixture, "php").substring(5);
        String[] wanted = resource(fixture, "expected").split("\n", -1);
        assertEquals("Native row count", rows + 1, wanted.length);
        assertEquals("Native trailing newline", "", wanted[rows]);
        ArrayList<String> differences = new ArrayList<>();
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            String mode = "debug=" + debug + " dump=" + dumped;
            var scope = newScope();
            scope.setDebugMode(debug);
            scope.registerExtension(new StandardExtension());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(scope, output);
            try {
                ModuleEntity module = compile(env, source, dumped);
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                module.include(env);
                env.getDefaultBuffer().flush();
                String[] actual = output.toString(StandardCharsets.UTF_8).split("\n", -1);
                if (actual.length != wanted.length) differences.add(mode + " row count=" + actual.length
                        + " expected=" + wanted.length + " full output=" + output.toString(StandardCharsets.UTF_8));
                for (int i = 0; i < Math.min(wanted.length, actual.length); i++) {
                    if (!wanted[i].equals(actual[i])) differences.add(mode + " row=" + (i + 1)
                            + " expected=" + wanted[i] + " actual=" + actual[i]);
                }
            } catch (Throwable failure) {
                differences.add(mode + " threw=" + failure.getClass().getName() + ":" + failure.getMessage());
            } finally { env.doFinal(); }
        }
        assertEquals("Native finally differences=" + differences.size() + "\n"
                        + String.join("\n", differences.subList(0, Math.min(20, differences.size()))),
                0, differences.size());
    }

    @Test public void testNativeReturnsExceptionsAndPreviousChains() throws Throwable {
        checkNative("php85_try_finally", 32);
    }

    /** Loop/switch target ancestry and lexical goto targets must execute exactly the exited finally blocks. */
    @Test public void testNativeFinallyOnExitingJumps() throws Throwable {
        checkNative("php85_try_finally_jumps", 40);
    }

    @Test public void testNativeForbiddenFinallyAndLoopTransfers() throws Throwable {
        String[][] controls = {
                {"while(true){try{}finally{break;}}", "jump out of a finally block is disallowed"},
                {"while(true){try{}finally{continue;}}", "jump out of a finally block is disallowed"},
                {"try{}finally{goto out;}out:;", "jump out of a finally block is disallowed"},
                {"goto inside;try{}finally{inside:;}", "jump into a finally block is disallowed"},
                {"goto inside;while(false){inside:;}", "'goto' into loop or switch statement is disallowed"},
                {"if(false){while(false){inside:;}}while(true){goto inside;}", "'goto' into loop or switch statement is disallowed"},
                {"try{}finally{goto inside;try{}finally{inside:;}}", "jump into a finally block is disallowed"}
        };
        for (boolean debug : new boolean[]{false, true}) for (String[] control : controls) {
            var scope = newScope();
            scope.setDebugMode(debug);
            Environment env = new Environment(scope, new ByteArrayOutputStream());
            try {
                Context context = new Context(control[0]);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {
                    new JvmCompiler(env, context).compile(false);
                    fail("Expected native compile-time error: " + control[0]);
                } catch (php.runtime.exceptions.support.ErrorException error) {
                    assertEquals(php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR, error.getType());
                    assertEquals(control[1], error.getMessage());
                }
            } finally { env.doFinal(); }
        }
    }

    @Test public void testNativeExitBypassesFinally() throws Throwable {
        String source = "echo 'before;';try {exit('exit;');} finally {echo 'finally;';}";
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();
            scope.setDebugMode(debug);
            scope.registerExtension(new StandardExtension());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(scope, output);
            try {
                ModuleEntity module = compile(env, source, dumped);
                try { module.include(env); fail("exit must stop module execution"); }
                catch (DieException exit) { assertEquals(0, exit.getExitCode()); }
                env.getDefaultBuffer().flush();
                assertEquals("debug=" + debug + " dump=" + dumped, "before;exit;",
                        output.toString(StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        }
    }
}
