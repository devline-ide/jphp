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
public class PhpNullsafeOperatorTest {
    private Environment environment(boolean debug) {
        CompileScope scope = new CompileScope();
        scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension()); scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        scope.setDebugMode(debug); return new Environment(scope, new ByteArrayOutputStream());
    }
    private List<String> corpus() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/functions/php85_nullsafe.tsv")) {
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
        assertEquals(276, checks);
        assertTrue(mismatches.size() + " mismatches: " + String.join("\n", mismatches.subList(0, Math.min(20, mismatches.size()))), mismatches.isEmpty());
    }
    @Test public void nativeNullsafeDebugSourceAndDump() throws Throwable { nativeOracle(true); }
    @Test public void nativeNullsafeOptimizedSourceAndDump() throws Throwable { nativeOracle(false); }

}
