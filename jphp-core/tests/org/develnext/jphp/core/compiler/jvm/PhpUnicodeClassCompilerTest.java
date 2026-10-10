package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.support.Entity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Native PHP 8.5.11 class-name compiler oracle, including diagnostics and optimized bytecode. */
public class PhpUnicodeClassCompilerTest extends JvmCompilerCase {
    private String resource(String suffix) throws IOException {
        String name = "resources/type_hinting/php85_unicode_class_compiler." + suffix;
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void verify(boolean debug) throws Throwable {
        for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope(); scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                String source = "<?php" + (strict ? "declare(strict_types=1);" : "") + resource("php").substring(5);
                var context = new Context(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                        "Standard input code(1) : eval()'d code", StandardCharsets.UTF_8);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                // All lookups must use the compiled module's profile even in a default legacy scope.
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                var entities = new ArrayList<Entity>(); entities.add(module);
                entities.addAll(module.getClasses()); entities.addAll(module.getFunctions());
                entities.addAll(module.getClosures()); entities.addAll(module.getGenerators());
                var diagnostics = new StringWriter(); var writer = new PrintWriter(diagnostics);
                for (Entity entity : entities) if (entity.getData() != null && entity.getData().length != 0)
                    CheckClassAdapter.verify(new ClassReader(entity.getData()), scope.getClassLoader(), false, writer);
                assertEquals("ASM debug=" + debug + " dump=" + dumped, "", diagnostics.toString());
                var actual = module.include(env);
                var trace = php.runtime.env.TraceInfo.valueOf(context.getFileName(), 0, 0, module.getInternalName());
                var expected = php.runtime.memory.output.serialization.PhpSerialization.unserialize(env, trace,
                        php.runtime.memory.StringMemory.valueOf(resource("expected")));
                var differences = new ArrayList<String>();
                assertEquals(38, actual.toValue(php.runtime.memory.ArrayMemory.class).size());
                for (int i = 0; i < 38; i++) {
                    String wanted = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, expected.valueOfIndex(i)).toString();
                    String got = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, actual.valueOfIndex(i)).toString();
                    if (!wanted.equals(got)) differences.add("row=" + i + " expected=" + wanted + " actual=" + got);
                }
                assertEquals("debug=" + debug + " strict=" + strict + " dump=" + dumped + "\n" + String.join("\n", differences), 0, differences.size());
            } finally { env.doFinal(); }
        }
    }

    private void compileError(String code, String message) throws Throwable {
        for (boolean strict : new boolean[]{false, true}) {
            var env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                var context = new Context((strict ? "declare(strict_types=1);" : "") + code);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {
                    new JvmCompiler(env, context).compile(false);
                    fail("Expected native compile-time error: " + code);
                } catch (php.runtime.exceptions.support.ErrorException error) {
                    assertEquals(php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR, error.getType());
                    assertEquals(message, error.getMessage());
                }
            } finally { env.doFinal(); }
        }
    }

    @Test public void testUnicodeCompilerWithDynamicLocals() throws Throwable { verify(true); }
    @Test public void testUnicodeCompilerWithOptimizedLocals() throws Throwable { verify(false); }

    @Test public void testAsciiTypesStillRejectDuplicateUnionAndIntersectionArms() throws Throwable {
        compileError("namespace UCC; class ASCII {} function bad(ASCII|ascii $value) {}",
                "Duplicate type UCC\\ascii is redundant");
        compileError("namespace UCC; interface ASCII {} function bad(ASCII&ascii $value) {}",
                "Duplicate type UCC\\ascii is redundant");
        compileError("namespace UCC; interface ASCII {} interface SECOND {} function bad((ASCII&SECOND)|(ascii&second) $value) {}",
                "Type UCC\\ascii&UCC\\second is redundant with type UCC\\ASCII&UCC\\SECOND");
    }

    @Test public void testDistinctUnicodeClassReturnTypesCannotOverrideEachOther() throws Throwable {
        compileError("namespace UCC; class Σ {} class σ {} class ParentType { function value(): Σ {} } class ChildType extends ParentType { function value(): σ {} }",
                "Declaration of UCC\\ChildType::value(): UCC\\σ must be compatible with UCC\\ParentType::value(): UCC\\Σ");
    }

    @Test public void testLegacyUnicodeLookupContractRemainsAvailable() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                var context = new Context("class UCCΣName {} class UCCΣ {} $middle = new UCCΣName; $final = new UCCΣ;"
                        + "return [$middle instanceof UCCσName, $final instanceof UCCσ];");
                context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                var result = module.include(env);
                assertTrue("Legacy folds a non-final sigma, dump=" + dumped, result.valueOfIndex(0).toBoolean());
                assertFalse("Legacy preserves Java final-sigma distinction, dump=" + dumped, result.valueOfIndex(1).toBoolean());
            } finally { env.doFinal(); }
        }
    }
}
