package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import org.develnext.jphp.zend.ext.standard.StandardExtension;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.*;

public class PhpAtomicTypesTest extends JvmCompilerCase {
    @Test public void testPhp85InvalidTypeDeclarations() throws Throwable {
        for (String source : new String[] {"class MiXeD {}", "namespace Example; class mixed {}",
                "interface P { function f(): ?void; }", "function f(): mixed { return; }",
                "function f(): ?int { return; }", "function f(): self {}"}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                Context context = new Context(source);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {
                    new JvmCompiler(env, context).compile(false);
                    fail(source);
                } catch (ErrorException expected) {
                    assertFalse(source, expected.getMessage().isEmpty());
                }
            } finally { env.doFinal(); }
        }
    }

    @Test public void testNativeMixedRuntimeContracts() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().registerExtension(new StandardExtension());
            try {
                String source, expected;
                try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_mixed_contract.php")) {
                    source = new String(input.readAllBytes(), StandardCharsets.UTF_8).substring(5);
                }
                try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_mixed_contract.expected")) {
                    expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                Context context = new Context(source);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                ModuleEntity module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    ModuleDumper dumper = new ModuleDumper(context, env, true);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                Memory result = module.include(env);
                var trace = php.runtime.env.TraceInfo.valueOf(context.getFileName(), 0, 0, module.getInternalName());
                assertEquals("dumped=" + dumped, expected, php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, result).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testNativeAtomicDeclarationMatrix() throws Throwable {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(getClass().getClassLoader()
                .getResourceAsStream("resources/type_hinting/php85_atomic_types.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                String[] fields = line.split("\t");
                Environment env = new Environment(newScope(), new ByteArrayOutputStream());
                try {
                    Context context = new Context(new String(Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8));
                    context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    ModuleEntity module;
                    try {
                        module = new JvmCompiler(env, context).compile(false);
                    } catch (ErrorException error) {
                        assertEquals(fields[0] + ": " + error.getMessage(), "reject", fields[1]);
                        continue;
                    }
                    assertEquals(fields[0], "accept", fields[1]);
                    // Dump validation must use the saved profile even after the host's default changes.
                    ModuleDumper dumper = new ModuleDumper(context, env, true);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);
                    env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                    ModuleEntity loaded = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    assertEquals(fields[0], PhpLanguageProfile.PHP_8_5, loaded.getPhpLanguageProfile());
                    env.getScope().loadModule(loaded);
                    env.getScope().addUserModule(loaded);
                    env.registerModule(loaded);
                    loaded.include(env);
                    StringWriter diagnostics = new StringWriter();
                    CheckClassAdapter.verify(new ClassReader(loaded.getData()), env.getScope().getClassLoader(),
                            false, new PrintWriter(diagnostics));
                    for (var clazz : loaded.getClasses()) {
                        if (clazz.getData() == null || clazz.getData().length == 0) continue;
                        CheckClassAdapter.verify(new ClassReader(clazz.getData()), env.getScope().getClassLoader(),
                                false, new PrintWriter(diagnostics));
                    }
                    assertEquals(fields[0], "", diagnostics.toString());
                } finally { env.doFinal(); }
            }
        }
    }

    @Test public void testMixedAndTypedArrayAccessExecution() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                Context context = new Context("function identity(mixed $x): mixed { return $x; } "
                        + "class Box implements ArrayAccess { private $values = []; "
                        + "function offsetExists(mixed $key): bool { return isset($this->values[$key]); } "
                        + "function offsetGet(mixed $key): mixed { return $this->values[$key]; } "
                        + "function offsetSet(mixed $key, mixed $value): void { $this->values[$key] = $value; } "
                        + "function offsetUnset(mixed $key): void { unset($this->values[$key]); } } "
                        + "$box = new Box; $box['a'] = identity(null); $box['b'] = identity(4); "
                        + "$reflection = new ReflectionFunction('identity'); "
                        + "$parameter = $reflection->getParameters()[0]->getType(); $return = $reflection->getReturnType(); "
                        + "$values = [identity(null), identity(true), identity(1), identity(2.5), identity('s'), identity([3])]; "
                        + "$out = [$values, identity($box) === $box, isset($box['a']), isset($box['b']), $box['b'], "
                        + "$parameter->getName(), $parameter->allowsNull(), $parameter->isBuiltin(), "
                        + "$return->getName(), $return->allowsNull(), $return->isBuiltin()]; "
                        + "unset($box['b']); $out[] = isset($box['b']); return $out;");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                ModuleEntity module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    ModuleDumper dumper = new ModuleDumper(context, env, true);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                Memory result = module.include(env);
                assertTrue(result.valueOfIndex(null, 0).valueOfIndex(null, 0).isNull());
                assertTrue(result.valueOfIndex(null, 1).toBoolean());
                assertFalse(result.valueOfIndex(null, 2).toBoolean());
                assertTrue(result.valueOfIndex(null, 3).toBoolean());
                assertEquals(4, result.valueOfIndex(null, 4).toLong());
                assertEquals("mixed", result.valueOfIndex(null, 5).toString());
                assertEquals("mixed", result.valueOfIndex(null, 8).toString());
                for (int index : new int[] {6, 7, 9, 10}) assertTrue(result.valueOfIndex(null, index).toBoolean());
                assertFalse(result.valueOfIndex(null, 11).toBoolean());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testLegacyMixedClassName() throws Throwable {
        Environment env = new Environment(newScope(), new ByteArrayOutputStream());
        try {
            ModuleEntity module = new JvmCompiler(env, new Context("class mixed {} function f(mixed $x): mixed { return $x; } return f(new mixed);"))
                    .compile(false);
            assertEquals("mixed", module.getFunctions().iterator().next().getParameters()[0].getTypeClass());
            assertEquals("mixed", module.getFunctions().iterator().next().getReturnTypeClass());
            env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
            assertTrue(module.include(env).isObject());
        } finally { env.doFinal(); }
    }

    @Test(timeout = 5000) public void testLegacyPrototypeChainTerminates() throws Throwable {
        Environment env = new Environment(newScope(), new ByteArrayOutputStream());
        try {
            // A warning may be promoted by the host; either way the old prototype loop must terminate.
            try {
                new JvmCompiler(env, new Context("class P { function f(int $x) {} } "
                        + "class Q extends P { function f(int $x) {} } class C extends Q { function f(string $x) {} }"))
                        .compile(false);
            } catch (ErrorException error) {
                assertTrue(error.getMessage(), error.getMessage().contains("compatible"));
            }
        } finally { env.doFinal(); }
    }

    @Test public void testDumpRejectsIncompatibleTypeMetadata() throws Throwable {
        Environment env = new Environment(newScope(), new ByteArrayOutputStream());
        try {
            Context context = new Context("interface P { function f(): int; } interface C extends P { function f(): int; }");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ModuleEntity module = new JvmCompiler(env, context).compile(false);
            module.findClass("C").findMethod("f").setReturnType(php.runtime.common.HintType.STRING);
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            dumper.save(module, bytes);
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            try {
                dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                fail("Invalid return contract in a dump was accepted");
            } catch (php.runtime.exceptions.CompileException error) {
                assertEquals(php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR, error.getType());
                assertTrue(error.getMessage(), error.getMessage().contains("compatible"));
            }
        } finally { env.doFinal(); }
    }

    @Test public void testVarianceAcrossModules() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                for (String source : new String[] {
                        "class A {} class B extends A {} class P { function f(B $x): A { return $x; } }",
                        "class C extends P { function f(A $x): B { return new B; } } return (new C)->f(new A);"}) {
                    Context context = new Context(source);
                    context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    ModuleEntity module = new JvmCompiler(env, context).compile(false);
                    if (dumped) {
                        ModuleDumper dumper = new ModuleDumper(context, env, true);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        dumper.save(module, bytes);
                        module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    }
                    env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                    env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                    Memory result = module.include(env);
                    if (source.startsWith("class C")) assertEquals("B", result.toValue(php.runtime.memory.ObjectMemory.class).getReflection().getName());
                }
            } finally { env.doFinal(); }
        }
    }
}
