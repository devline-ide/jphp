package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.support.Entity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Exact global-script controls for array storage captured before an undefined-key warning. */
public class PhpIncrementArrayCaptureTest extends JvmCompilerCase {
    private String resource(String name, String suffix) throws IOException {
        String path = "resources/type_hinting/php85_increment_array_capture_" + name + "." + suffix;
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

    /** Retained VALUE copies reject continuation; released copies and reference aliases remain writable. */
    @Test public void testNativeArrayWarningOwnerControls() throws Throwable {
        String[] names = {"readonly_peek", "value_copy_survives", "value_copy_unset", "reference_alias",
                "arrayaccess_byref_once", "arrayaccess_byvalue_once"};
        ArrayList<String> differences = new ArrayList<>();
        int comparisons = 0;
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            for (String name : names) {
                String mode = name + " debug=" + debug + " dump=" + dumped;
                var scope = newScope();
                scope.setDebugMode(debug);
                scope.registerExtension(new StandardExtension());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                Environment env = new Environment(scope, output);
                try {
                    ModuleEntity module = compile(env, resource(name, "php").substring(5), dumped);
                    scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                    module.include(env);
                    env.getDefaultBuffer().flush();
                    String wanted = resource(name, "expected");
                    String actual = output.toString(StandardCharsets.UTF_8);
                    if (!wanted.equals(actual)) differences.add(mode + " expected=" + wanted + " actual=" + actual);
                } catch (Throwable failure) {
                    differences.add(mode + " threw=" + failure.getClass().getName() + ":" + failure.getMessage());
                } finally { env.doFinal(); }
                comparisons++;
            }
        }
        assertEquals(24, comparisons);
        assertEquals("Native owner-control differences=" + differences.size() + "\n" + String.join("\n", differences),
                0, differences.size());
    }
}
