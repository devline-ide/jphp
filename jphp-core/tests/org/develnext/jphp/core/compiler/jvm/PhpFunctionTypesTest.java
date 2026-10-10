package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.reflection.ModuleEntity;

import static org.junit.Assert.*;

public class PhpFunctionTypesTest extends JvmCompilerCase {
    @Test public void testNativeFunctionRuntime() throws Throwable {oracle("php85_function_runtime"); oracle("php85_function_strict");}

    @Test public void testNativeFunctionDeclarations() throws Throwable {
        var mismatches = new ArrayList<String>();
        for (String line : resource("php85_function_types.tsv").split("\\R")) {
            String[] fields = line.split("\t");
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                Context context = new Context(new String(Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                ModuleEntity module;
                try {
                    module = new JvmCompiler(env, context).compile(false);
                } catch (ErrorException error) {
                    if (!fields[1].equals("reject")) mismatches.add(fields[0] + ": " + error.getMessage());
                    continue;
                }
                if (!fields[1].equals("accept")) {
                    mismatches.add(fields[0] + ": unexpectedly accepted");
                    continue;
                }
                var dumper = new ModuleDumper(context, env, true);
                var bytes = new ByteArrayOutputStream();
                dumper.save(module, bytes);
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                module.include(env);
                verify(module, env);
            } finally { env.doFinal(); }
        }
        assertEquals(String.join("\n", mismatches), 0, mismatches.size());
    }

    private void oracle(String fixture) throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().registerExtension(new StandardExtension());
            try {
                Context context = new Context(resource(fixture + ".php").substring(5));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);
                    var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                verify(module, env);
                Memory result = module.include(env);
                var trace = php.runtime.env.TraceInfo.valueOf(context.getFileName(), 0, 0, module.getInternalName());
                Memory expected = php.runtime.memory.output.serialization.PhpSerialization.unserialize(env, trace,
                        php.runtime.memory.StringMemory.valueOf(resource(fixture + ".expected")));
                var differences = new ArrayList<String>();
                assertEquals(expected.toValue(php.runtime.memory.ArrayMemory.class).size(), result.toValue(php.runtime.memory.ArrayMemory.class).size());
                for (int i=0; i<expected.toValue(php.runtime.memory.ArrayMemory.class).size(); i++) {
                    String wanted = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, expected.valueOfIndex(i)).toString();
                    String actual = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, result.valueOfIndex(i)).toString();
                    if (!wanted.equals(actual)) differences.add("row=" + i + " expected=" + wanted + " actual=" + actual);
                }
                assertEquals("fixture=" + fixture + " dumped=" + dumped + " differences=" + differences.size() + "\n"
                        + String.join("\n", differences.subList(0, Math.min(30, differences.size()))), 0, differences.size());
            } finally { env.doFinal(); }
        }
    }

    private void verify(ModuleEntity module, Environment env) {
        var diagnostics = new StringWriter();
        var writer = new PrintWriter(diagnostics);
        var loader = env.getScope().getClassLoader();
        CheckClassAdapter.verify(new ClassReader(module.getData()), loader, false, writer);
        for (var clazz : module.getClasses()) {
            if (clazz.getData() != null && clazz.getData().length != 0)
                CheckClassAdapter.verify(new ClassReader(clazz.getData()), loader, false, writer);
        }
        for (var closure : module.getClosures())
            CheckClassAdapter.verify(new ClassReader(closure.getData()), loader, false, writer);
        for (var generator : module.getGenerators())
            CheckClassAdapter.verify(new ClassReader(generator.getData()), loader, false, writer);
        assertEquals("", diagnostics.toString());
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/" + name)) {
            assertNotNull(name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
