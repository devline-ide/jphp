package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.Callback;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.PhpFiber;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Native deferred-output diagnostics, supplementary to unchanged output9/84. No Java run in staging. */
public class PhpOutputHandlerDeprecationTest {
    private static Path ROOT;
    @BeforeClass public static void origins() throws Exception {
        ROOT = PhpPortableTestInputs.fixture("qa.output.deprecation", "output-handler-deprecation");
        Path expected = PhpPortableTestInputs.runtimeOrigin();
        for (Class<?> type : List.of(PhpFiber.class, Environment.class, ConcurrentEnvironment.class, php.runtime.output.OutputBuffer.class))
            assertEquals(type.getName(), expected, Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static CompileScope scope(boolean debug) {
        var scope = new CompileScope();
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());return scope;
    }
    private static Environment environment(boolean concurrent, boolean debug, OutputStream sink) {
        Environment env = concurrent ? new ConcurrentEnvironment(scope(debug), sink) : new Environment(scope(debug), sink);
        env.getDefaultBuffer().setImplicitFlush(true);return env;
    }
    private static php.runtime.reflection.ModuleEntity compile(Environment env, String source, boolean phb) throws Throwable {
        var context = new Context(source.startsWith("<?php") ? source.substring(5) : source);
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(false);
        if (phb) {
            var bytes = new ByteArrayOutputStream();var codec = new ModuleDumper(context, env, true);
            codec.save(module, bytes);assertTrue(bytes.size() > 100);
            module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }

    private static void compare(List<String> cases) throws Throwable {
        for (String name : cases) for (boolean concurrent : new boolean[]{false, true})
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var out = new ByteArrayOutputStream();var env = environment(concurrent, debug, out);
                try {
                    compile(env, Files.readString(ROOT.resolve("native/" + name + ".php"), StandardCharsets.UTF_8), phb).include(env);env.flushAll();
                    assertEquals(name + ":concurrent=" + concurrent + ":debug=" + debug + ":phb=" + phb,
                            Files.readString(ROOT.resolve("native/" + name + ".stdout"), StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
                } finally {env.doFinal();}
            }
    }
    @Test(timeout=60000) public void deferredHandlerDiagnosticsAndFallbackSourceAndRealPhb() throws Throwable {
        compare(List.of("deferred-phase", "empty", "twice", "throw", "false", "errorhandlerthrow", "errorhandleroutput"));
    }
    @Test(timeout=60000) public void diagnosticCallSiteAndNamedHandlerSourceAndRealPhb() throws Throwable {
        compare(List.of("message-final", "message-automatic", "message-flush", "message-clean"));
    }


    @org.junit.AfterClass public static void releasePortableFixtures() throws Exception {
        Path owned = ROOT;ROOT = null;PhpPortableTestInputs.release(owned);
    }
}
