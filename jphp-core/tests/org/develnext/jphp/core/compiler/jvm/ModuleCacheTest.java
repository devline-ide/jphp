package org.develnext.jphp.core.compiler.jvm;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import php.runtime.Memory;
import php.runtime.common.CompilerFactory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.ModuleCacheKey;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class ModuleCacheTest extends JvmCompilerCase {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void testReloadIdenticalDumpReusesNativeDefinitions() throws Throwable {
        Environment env = new Environment(newScope(), new ByteArrayOutputStream());
        try {
            Context context = new Context("function dumpedFunction() { return 3; } " +
                    "class DumpedClass { function value() { return 4; } } " +
                    "function dumpedGenerator() { yield 5; } " +
                    "$closure = function() { return 6; }; $sum = 0; " +
                    "foreach (dumpedGenerator() as $v) $sum += $v; " +
                    "return dumpedFunction() + (new DumpedClass)->value() + $sum + $closure();");
            ModuleEntity original = new JvmCompiler(env, context).compile(false);
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            dumper.save(original, bytes);
            env.getScope().loadModule(original);
            env.getScope().addUserModule(original);
            env.registerModule(original);
            assertEquals(18, original.include(env).toLong());
            for (int i = 0; i < 3; i++) {
                ModuleEntity reloaded = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                env.getScope().loadModule(reloaded);
                env.getScope().addUserModule(reloaded);
                assertSame(original.getNativeClazz(), reloaded.getNativeClazz());
                assertSame(original.getFunctions().iterator().next().getNativeClazz(),
                        reloaded.getFunctions().iterator().next().getNativeClazz());
                assertEquals(18, reloaded.include(env).toLong());
            }
            ModuleEntity changed = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            byte[] changedBytes = changed.getData().clone();
            changedBytes[changedBytes.length - 1] ^= 1;
            changed.setData(changedBytes);
            try { env.getScope().loadModule(changed); fail("Different bytecode accepted for an existing class"); }
            catch (LinkageError expected) { assertTrue(expected.getMessage().contains("Different bytecode")); }
            assertFalse(changed.isLoaded());
            assertEquals(18, original.include(env).toLong());
        } finally { env.doFinal(); }
    }

    private File source(String name, String code) throws Exception {
        File file = temporary.newFile(name);
        Files.writeString(file.toPath(), "<?php " + code, StandardCharsets.UTF_8);
        return file;
    }

    private String quoted(File file) { return "'" + file.getPath().replace("\\", "/").replace("'", "\\'") + "'"; }

    @Test public void testFileIdentityDoesNotDependOnLocale() throws Throwable {
        File file = source("INCLUDE-I.php", "return 17;");
        Locale previous = Locale.getDefault();
        Environment env = new Environment(newScope());
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            ModuleEntity first = env.importModule(new Context(file));
            Locale.setDefault(Locale.US);
            ModuleEntity second = env.importModule(new Context(file));
            assertSame(first, second);
            assertEquals(17, second.include(env).toLong());
        } finally {
            Locale.setDefault(previous);
            env.doFinal();
        }
    }

    private Memory run(Environment env, String code, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Context context = new Context(code);
        return runContext(env, context, profile, dumped);
    }

    private Memory runContext(Environment env, Context context, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        context.setPhpLanguageProfile(profile);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().setPhpLanguageProfile(profile == PhpLanguageProfile.PHP_8_5
                ? PhpLanguageProfile.JPHP_LEGACY : PhpLanguageProfile.PHP_8_5);
        env.getScope().loadModule(module);
        env.registerModule(module);
        return module.include(env);
    }

    @Test public void testCallerProfileAndSharedScopeCache() throws Throwable {
        File nested = source("nested.php", "return 0 == 'abc';");
        File file = source("profiles.php", "return [0 == 'abc', eval('return 0 == \"abc\";'), include " + quoted(nested) + "];");
        for (boolean dumped : new boolean[] {false, true}) {
            CompileScope scope = newScope();
            CompilerFactory original = scope.getCompilerFactory();
            AtomicInteger compilations = new AtomicInteger();
            scope.setCompilerFactory(new CompilerFactory() {
                @Override public php.runtime.common.AbstractCompiler getCompiler(Environment env, Context context) throws Throwable {
                    compilations.incrementAndGet();
                    return original.getCompiler(env, context);
                }
            });
            Environment env = new Environment(scope);
            env.setErrorFlags(0);
            try {
                for (PhpLanguageProfile profile : new PhpLanguageProfile[] {PhpLanguageProfile.PHP_8_5, PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5}) {
                    Memory result = run(env, "return include " + quoted(file) + ";", profile, dumped);
                    for (int i = 0; i < 3; i++) assertEquals(profile == PhpLanguageProfile.JPHP_LEGACY, result.valueOfIndex(null, i).toBoolean());
                }
                // Two files in each profile; eval is also compiled on every execution.
                assertEquals(7, compilations.get());
                ModuleEntity modern = env.getModuleManager().fetchModule(file.getPath(), false, PhpLanguageProfile.PHP_8_5);
                Environment sibling = new Environment(new CompileScope(scope));
                sibling.setErrorFlags(0);
                try {
                    ModuleEntity reused = sibling.getModuleManager().fetchModule(file.getPath(), false, PhpLanguageProfile.PHP_8_5);
                    assertSame(modern, reused);
                    assertEquals(7, compilations.get());
                    assertFalse(sibling.getModuleManager().isIncluded(file.getPath()));
                } finally { sibling.doFinal(); }
            } finally { env.doFinal(); }
        }
    }

    @Test public void testCompiledProfileAndSourceKindAreIndependent() throws Throwable {
        File file = temporary.newFile("artifact.php");
        Environment env = new Environment(newScope());
        env.setErrorFlags(0);
        try {
            Context context = new Context("<?php return 0 == 'abc';", file);
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ModuleEntity template = new JvmCompiler(env, context).compile(true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new ModuleDumper(context, env, true).save(template, bytes);
            Files.write(file.toPath(), bytes.toByteArray());
            ModuleEntity loaded = env.getModuleManager().fetchModule(file.getPath(), true, PhpLanguageProfile.JPHP_LEGACY);
            assertTrue(loaded.isCompiledArtifact());
            assertEquals(PhpLanguageProfile.PHP_8_5, loaded.getPhpLanguageProfile());
            assertFalse(loaded.include(env).toBoolean());
            assertSame(loaded, env.getModuleManager().fetchModule(file.getPath(), true, PhpLanguageProfile.PHP_8_5));
            assertSame(loaded, env.importCompiledModule(new Context(file), true));
            assertSame(template, env.getScope().findCachedModule(file.getPath(), false, PhpLanguageProfile.PHP_8_5));
            assertNotEquals(new ModuleCacheKey(file.getPath(), true, PhpLanguageProfile.PHP_8_5),
                    new ModuleCacheKey(file.getPath(), false, PhpLanguageProfile.PHP_8_5));
        } finally { env.doFinal(); }
    }

    @Test public void testSourceModificationInvalidatesSharedScope() throws Throwable {
        File file = source("modified.php", "return 1;");
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1000000000000L));
        CompileScope scope = newScope();
        Environment first = new Environment(scope);
        Environment second = new Environment(scope);
        try {
            ModuleEntity old = first.getModuleManager().fetchModule(file.getPath());
            assertEquals(1, old.include(first).toLong());
            assertSame(old, second.getModuleManager().fetchModule(file.getPath()));
            Files.writeString(file.toPath(), "<?php return 2;", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1000000002000L));
            ModuleEntity updated = second.getModuleManager().fetchModule(file.getPath());
            assertNotSame(old, updated);
            assertEquals(2, updated.include(second).toLong());
            assertSame(updated, first.getModuleManager().fetchModule(file.getPath()));
            assertEquals(2, first.importModule(new Context(file)).include(first).toLong());
            assertEquals(1000000002000L, new Context(file).getLastModified());
        } finally { first.doFinal(); second.doFinal(); }
    }

    @Test public void testAnonymousContentHashCollision() throws Throwable {
        String first = "return 'Aa';", second = "return 'BB';";
        assertEquals(first.hashCode(), second.hashCode());
        Environment env = new Environment(newScope());
        try {
            assertNotEquals(new Context(first).getModuleName(), new Context(second).getModuleName());
            assertEquals("Aa", env.importModule(new Context(first)).include(env).toString());
            assertEquals("BB", env.importModule(new Context(second)).include(env).toString());
            assertEquals("Aa", env.importModule(new Context(first)).include(env).toString());
            assertTrue(env.getModuleManager().getIncludedPaths().isEmpty());
        } finally { env.doFinal(); }
    }

    @Test public void testConcurrentImportPublishesReadyModulesOnce() throws Throwable {
        File file = source("concurrent.php", "return 0 == 'abc';");
        CompileScope scope = newScope();
        CompilerFactory original = scope.getCompilerFactory();
        AtomicInteger compilations = new AtomicInteger();
        scope.setCompilerFactory(new CompilerFactory() {
            @Override public php.runtime.common.AbstractCompiler getCompiler(Environment env, Context context) throws Throwable {
                compilations.incrementAndGet(); return original.getCompiler(env, context);
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ModuleEntity>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                PhpLanguageProfile profile = PhpLanguageProfile.values()[i % 2];
                futures.add(workers.submit(() -> {
                    start.await();
                    Environment env = new Environment(scope);
                    try {
                        Context context = new Context(file); context.setPhpLanguageProfile(profile);
                        ModuleEntity module = env.importModule(context);
                        assertEquals(profile, module.getPhpLanguageProfile());
                        assertEquals(profile == PhpLanguageProfile.JPHP_LEGACY, module.include(env).toBoolean());
                        scope.loadModule(module);
                        return module;
                    } catch (Throwable failure) { throw new RuntimeException(failure); }
                    finally {
                        try { env.doFinal(); }
                        catch (Throwable failure) { throw new RuntimeException(failure); }
                    }
                }));
            }
            start.countDown();
            ModuleEntity[] byProfile = new ModuleEntity[2];
            for (int i = 0; i < futures.size(); i++) {
                ModuleEntity module = futures.get(i).get(30, TimeUnit.SECONDS);
                if (byProfile[i % 2] == null) byProfile[i % 2] = module;
                else assertSame(byProfile[i % 2], module);
            }
            assertEquals(2, compilations.get());
        } finally { workers.shutdownNow(); }
    }

    @Test public void testNativeOnceContractBothProfilesAndDump() throws Throwable {
        File file = new File(getClass().getClassLoader().getResource("resources/include/php8_once_contract.php").toURI());
        String expected = Files.readString(file.toPath().resolveSibling("php8_once_contract.expected"), StandardCharsets.UTF_8).trim();
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            for (boolean dumped : new boolean[] {false, true}) {
                Environment env = new Environment(newScope()); env.setErrorFlags(0);
                try { assertEquals(expected, runContext(env, new Context(file), profile, dumped).toString()); }
                finally { env.doFinal(); }
            }
        }
    }

    @Test public void testOnceTracksExecutionAndCanonicalAliases() throws Throwable {
        File file = source("counter.php", "$counter++; return $counter;");
        File self = source("self.php", "$counter++; return include_once __FILE__;");
        Environment env = new Environment(newScope());
        env.setErrorFlags(0);
        try {
            env.getGlobals().put("counter", Memory.CONST_INT_0);
            env.getModuleManager().fetchModule(file.getPath());
            assertTrue(env.getModuleManager().getIncludedPaths().isEmpty());
            assertEquals(1, env.__includeOnce(file.getPath(), env.getGlobals(), TraceInfo.UNKNOWN).toLong());
            String alias = new File(file.getParentFile(), "./counter.php").getPath();
            assertSame(Memory.TRUE, env.__requireOnce(alias, env.getGlobals(), TraceInfo.UNKNOWN));
            assertEquals(1, env.getGlobals().valueOfIndex("counter").toLong());
            assertEquals(1, env.getModuleManager().getIncludedPaths().size());
            assertSame(Memory.TRUE, env.__includeOnce(self.getPath(), env.getGlobals(), TraceInfo.UNKNOWN));
            assertEquals(2, env.getGlobals().valueOfIndex("counter").toLong());
            assertEquals(2, env.eval("return count(get_included_files());").toLong());
        } finally { env.doFinal(); }
    }
}
