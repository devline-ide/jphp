package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.lang.exception.BaseBaseException;
import php.runtime.lang.exception.BaseError;
import php.runtime.loader.dump.*;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Pinned PHP8.5.11 sources and exact diagnostics, independently executed in all bytecode modes. */
public class PhpFirstClassCallableTest {
    private Environment environment(boolean debug) {
        CompileScope scope = new CompileScope();
        scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension()); scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        scope.setDebugMode(debug); return new Environment(scope, new ByteArrayOutputStream());
    }
    private List<String> corpus() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/functions/php85_first_class_callables.tsv")) {
            assertNotNull(input); return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
    private String decode(String text) { return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }
    private String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("/", "\\/").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    private void verify(byte[] data, Environment env) {
        if (data == null) return;
        StringWriter errors = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(data), env.getScope().getClassLoader(), false, new PrintWriter(errors));
        assertEquals(errors.toString(), "", errors.toString());
    }
    private void nativeOracle(boolean debug) throws Throwable {
        List<String> mismatches = new ArrayList<>(); int checks = 0;
        for (String row : corpus()) for (boolean dumped : new boolean[]{false, true}) {
            String[] fields = row.split("\t"); String source = decode(fields[2]), expected = decode(fields[3]);
            var scope = new CompileScope(); scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension()); scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension()); scope.setDebugMode(debug);
            var output = new ByteArrayOutputStream(); var env = new Environment(scope, output); String diagnostic = "";
            try {
                var context = new Context((Boolean.parseBoolean(fields[1]) ? "declare(strict_types=1);" : "") + source);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) { var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray())); }
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                verify(module.getData(), env);
                for (var function : module.getFunctions()) verify(function.getData(), env);
                for (var clazz : module.getClasses()) verify(clazz.getData(), env);
                for (var closure : module.getClosures()) verify(closure.getData(), env);
                module.include(env);
            } catch (BaseBaseException failure) {
                String message = failure.getMessage(env).toString();
                diagnostic = failure instanceof BaseError && ((BaseError) failure).getErrorType().toLong() == 64
                        ? "\\nFATAL:[64," + quote(message) + "]"
                        : "\\nERROR:[" + quote(failure.getReflection().getName()) + "," + quote(message) + "]";
            } catch (php.runtime.exceptions.support.ErrorException failure) {
                if (failure.getType().value != 64) throw failure;
                diagnostic = "\\nFATAL:[64," + quote(failure.getMessage()) + "]";
            } finally { env.doFinal(); }
            String actual = output.toString(StandardCharsets.UTF_8) + diagnostic; checks++;
            if (!expected.equals(actual)) mismatches.add(fields[0] + " strict=" + fields[1] + " debug=" + debug
                    + " dump=" + dumped + " expected=" + expected + " actual=" + actual);
        }
        assertEquals(348, checks);
        assertTrue(mismatches.size() + " mismatches: " + String.join("\n", mismatches.subList(0, Math.min(20, mismatches.size()))), mismatches.isEmpty());
    }
    @Test public void nativeFirstClassCallablesDebugSourceAndDump() throws Throwable { nativeOracle(true); }
    @Test public void nativeFirstClassCallablesOptimizedSourceAndDump() throws Throwable { nativeOracle(false); }

    @Test public void acquisitionAstNeedsNoEnvironmentAndLegacyStillRejectsPlaceholder() throws Throwable {
        var context = new Context("$c=missingTarget(...);"); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var analyzer = new org.develnext.jphp.core.syntax.SyntaxAnalyzer(null, new org.develnext.jphp.core.tokenizer.Tokenizer(context));
        assertNull(analyzer.getEnvironment()); assertEquals(1, analyzer.getTree().size());
        var expression = (org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken) analyzer.getTree().getFirst();
        assertNull(expression.getAsmExpr());
        var call = (org.develnext.jphp.core.tokenizer.token.expr.value.CallExprToken) expression.getTokens().getLast();
        assertTrue(call.isFirstClassCallable()); assertTrue(call.getParameters().isEmpty());
        var env = environment(false);
        try { var legacy = new Context("$c=strlen(...);"); legacy.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            try { new JvmCompiler(env, legacy).compile(false); fail("Legacy placeholder accepted"); }
            catch (php.runtime.exceptions.support.ErrorException expected) { assertNotNull(expected.getMessage()); }
            catch (BaseBaseException expected) { assertFalse(expected.getMessage(env).toString().isEmpty()); }
        } finally { env.doFinal(); }
    }

    @Test public void deferredParameterDescriptorSurvivesFreshLoaderAndRejectsMalformedMetadata() throws Throwable {
        var env = environment(false);
        try {
            var context = new Context("function callableDefault($c=strlen(...)){return $c('abc');}return callableDefault();");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5); var module = new JvmCompiler(env, context).compile(false);
            var parameter = module.findFunction("callableDefault").getParameters()[0];
            assertNotNull(parameter.getDefaultInitializer()); assertEquals(module.getInternalName(), parameter.getDefaultInitializer().moduleName());
            assertNull(parameter.getDefaultInitializer().declarationScope()); assertFalse(parameter.isNullableOrDefaultNull());
            var bytes = new ByteArrayOutputStream(); new ModuleDumper(context, env, true).save(module, bytes);
            assertEquals(ModuleDumper.COMPILER_ABI_VERSION, ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());
            var fresh = environment(false);
            try {
                var loaded = new ModuleDumper(context, fresh, true).load(new ByteArrayInputStream(bytes.toByteArray()));
                fresh.getScope().loadModule(loaded); fresh.getScope().addUserModule(loaded); fresh.registerModule(loaded);
                assertEquals(3, loaded.include(fresh).toLong()); assertEquals(3, loaded.include(fresh).toLong());
                assertEquals(parameter.getDefaultInitializer(), loaded.findFunction("callableDefault").getParameters()[0].getDefaultInitializer());
            } finally { fresh.doFinal(); }
            var dumper = new ParameterDumper(context, env, true); var encoded = new ByteArrayOutputStream(); dumper.save(parameter, encoded);
            byte[] good = encoded.toByteArray(); int stamp = -1;
            for (int i = 0; i <= good.length - 4; i++) if (good[i] == 0x50 && good[i+1] == 0x44 && good[i+2] == 0x49 && good[i+3] == 0x4e) stamp = i;
            assertTrue(stamp >= 4); byte[] version = good.clone(); version[stamp+7] = 2;
            byte[] trailing = Arrays.copyOf(good, good.length+1); int size = good.length-stamp+1;
            for (int i = 0; i < 4; i++) trailing[stamp-4+i] = (byte)(size >>> (24-i*8));
            for (byte[] bad : new byte[][]{version, trailing, Arrays.copyOf(good, good.length-1)}) {
                try { dumper.load(new ByteArrayInputStream(bad)); fail("Malformed executable metadata accepted"); }
                catch (IOException expected) { }
            }
            var old = new ParameterEntity(context); old.setName("old"); old.setTypeChecker(PhpTypeDeclaration.fromChecker(php.runtime.reflection.support.TypeChecker.of("int"), false));
            encoded.reset(); dumper.save(old, encoded); var restored = dumper.load(new ByteArrayInputStream(encoded.toByteArray()));
            assertNull(restored.getDefaultInitializer()); assertEquals("int", restored.getTypeChecker().getSignature());
        } finally { env.doFinal(); }
    }
}
