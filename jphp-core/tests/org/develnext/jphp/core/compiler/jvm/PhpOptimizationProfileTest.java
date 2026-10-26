package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.PhpArithmetic;
import php.runtime.PhpPropertyWriteContext;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.ModuleCacheKey;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Numeric policy is lexical/artifact-owned and independent of scalar-specialization eligibility. */
public class PhpOptimizationProfileTest extends JvmCompilerCase {
    private static final PhpOptimizationProfile COMPATIBLE = PhpOptimizationProfile.COMPATIBLE;
    private static final PhpOptimizationProfile FAST = PhpOptimizationProfile.FAST_INT64_WRAP;

    @Test public void defaultsOverridesAndCacheIdentity() throws Throwable {
        CompileScope scope = newScope();
        assertSame(COMPATIBLE, scope.getPhpOptimizationProfile());
        Context context = new Context("return 1;");
        assertSame(COMPATIBLE, context.getPhpOptimizationProfile(scope));
        scope.setPhpOptimizationProfile(FAST);
        assertSame(FAST, context.getPhpOptimizationProfile(scope));
        context.setPhpOptimizationProfile(COMPATIBLE);
        assertSame(COMPATIBLE, context.getPhpOptimizationProfile(scope));
        assertSame(FAST, new CompileScope(scope).getPhpOptimizationProfile());
        assertSame(FAST, PhpOptimizationProfile.fromId(1));
        try { PhpOptimizationProfile.fromId(99); fail("Unknown profile accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("99")); }
        assertNotEquals(new ModuleCacheKey("memory://same.php", false, PhpLanguageProfile.PHP_8_5, "", COMPATIBLE),
                new ModuleCacheKey("memory://same.php", false, PhpLanguageProfile.PHP_8_5, "", FAST));
        assertEquals(new ModuleCacheKey("memory://same.phb", true, PhpLanguageProfile.PHP_8_5, "", COMPATIBLE),
                new ModuleCacheKey("memory://same.phb", true, PhpLanguageProfile.JPHP_LEGACY, "", FAST));
        Environment env = new Environment(scope, OutputStream.nullOutputStream());
        try {
            String source = "return 9223372036854775807 + 1;";
            Context ordinary = source(source, "memory://same.php", COMPATIBLE);
            Context fast = source(source, "memory://same.php", FAST);
            ModuleEntity one = env.importModule(ordinary), two = env.importModule(fast);
            assertNotSame(one, two);
            assertSame(one, env.importModule(source(source, "memory://same.php", COMPATIBLE)));
            assertEquals(Memory.Type.DOUBLE, one.include(env).type);
            assertEquals(Long.MIN_VALUE, two.include(env).toLong());
            Context invalid = new Context("return 1;"); invalid.setPhpOptimizationProfile(FAST);
            invalid.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            try { new JvmCompiler(env, invalid); fail("Fast legacy compilation accepted"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("PHP_8_5")); }
        } finally { env.doFinal(); }
    }

    @Test public void foldedDynamicAndMutationArithmeticMatchAcrossSourceAndPhb() throws Throwable {
        String code = "function identity($x){return $x;} " +
                "$max=identity(9223372036854775807); $min=~$max; " +
                "$local=$max; $local++; $arr=[$max]; ++$arr[0]; " +
                "$ref=$max; $alias=&$ref; $alias+=1; " +
                "return [9223372036854775807+1,$max+1,$min-1,$max*2,-$min," +
                "$local,$arr[0],$ref,identity(2)+identity(3),$max+0.0,$min/-1,$min%-1,2**63," +
                "identity('9223372036854775807')+1];";
        for (boolean dumped : new boolean[]{false, true}) for (PhpOptimizationProfile profile : PhpOptimizationProfile.values()) {
            Memory result = run(code, profile, dumped);
            boolean fast = profile == FAST;
            long[] wrapped = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, -2, Long.MIN_VALUE,
                    Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
            for (int i = 0; i < wrapped.length; i++) {
                Memory value = result.valueOfIndex(null, i).toValue();
                assertEquals("type " + i + " " + profile + " dumped=" + dumped, fast ? Memory.Type.INT : Memory.Type.DOUBLE, value.type);
                if (fast) assertEquals(wrapped[i], value.toLong());
            }
            assertEquals(5, result.valueOfIndex(null, 8).toLong());
            assertEquals(Memory.Type.DOUBLE, result.valueOfIndex(null, 9).toValue().type);
            assertEquals(Memory.Type.DOUBLE, result.valueOfIndex(null, 10).toValue().type);
            assertEquals(0, result.valueOfIndex(null, 11).toLong());
            assertEquals(Memory.Type.DOUBLE, result.valueOfIndex(null, 12).toValue().type);
            Memory numericString = result.valueOfIndex(null, 13).toValue();
            assertEquals(fast ? Memory.Type.INT : Memory.Type.DOUBLE, numericString.type);
            if (fast) assertEquals(Long.MIN_VALUE, numericString.toLong());
        }
    }

    @Test public void calleeProfileAndLegacyEntryDoNotInheritCaller() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
            try {
                load(env, "function compatibleAdd($a,$b){return $a+$b;}", COMPATIBLE, dumped).include(env);
                load(env, "function fastAdd($a,$b){return $a+$b;}", FAST, dumped).include(env);
                for (PhpOptimizationProfile caller : PhpOptimizationProfile.values()) {
                    env.scope.setPhpOptimizationProfile(caller == FAST ? COMPATIBLE : FAST);
                    ModuleEntity module = load(env, "return [compatibleAdd(9223372036854775807,1),fastAdd(9223372036854775807,1)];", caller, dumped);
                    Memory result = module.include(env);
                    assertEquals(Memory.Type.DOUBLE, result.valueOfIndex(null, 0).toValue().type);
                    assertEquals(Long.MIN_VALUE, result.valueOfIndex(null, 1).toLong());
                }
                var outer = PhpPropertyWriteContext.enter(env, null, false, FAST.getId());
                try {
                    assertEquals(Long.MIN_VALUE, PhpArithmetic.binary(LongMemory.valueOf(Long.MAX_VALUE), LongMemory.valueOf(1), "plus", env, null).toLong());
                    var oldEntry = PhpPropertyWriteContext.enter(env, null, false);
                    try { assertEquals(Memory.Type.DOUBLE, PhpArithmetic.binary(LongMemory.valueOf(Long.MAX_VALUE), LongMemory.valueOf(1), "plus", env, null).type); }
                    finally { PhpPropertyWriteContext.leave(oldEntry); }
                    assertSame(FAST, PhpPropertyWriteContext.current(env).optimizationProfile);
                } finally { PhpPropertyWriteContext.leave(outer); }
                assertNull(PhpPropertyWriteContext.current(env));
            } finally { env.doFinal(); }
        }
    }

    @Test public void typedPropertyAndPostDecrementUseTheSameOverflowRule() throws Throwable {
        String code = "$box=new class { public int $value=9223372036854775807; }; $error=null; " +
                "try { ++$box->value; } catch (\\Throwable $caught) { $error='overflow'; } " +
                "$min=~9223372036854775807; $before=$min--; return [$box->value,$error,$before,$min];";
        for (boolean dumped : new boolean[]{false, true}) for (PhpOptimizationProfile profile : PhpOptimizationProfile.values()) {
            Memory result=run(code,profile,dumped);
            assertEquals(profile==FAST?Long.MIN_VALUE:Long.MAX_VALUE,result.valueOfIndex(null,0).toLong());
            assertEquals(profile==FAST,"".equals(result.valueOfIndex(null,1).toString()));
            assertEquals(Long.MIN_VALUE,result.valueOfIndex(null,2).toLong());
            if(profile==FAST)assertEquals(Long.MAX_VALUE,result.valueOfIndex(null,3).toLong());
            else assertEquals(Memory.Type.DOUBLE,result.valueOfIndex(null,3).toValue().type);
        }
    }

    @Test public void evalRetainsItsLexicalNumericProfile() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) for (PhpOptimizationProfile profile : PhpOptimizationProfile.values()) {
            Memory result=run("return eval('return 9223372036854775807+1;');",profile,dumped);
            if(profile==FAST)assertEquals(Long.MIN_VALUE,result.toLong());
            else assertEquals(Memory.Type.DOUBLE,result.type);
        }
    }

    @Test public void sourceResolverIsFrozenAndDumpProfileSurvivesAnOppositeHostDefault() throws Throwable {
        Path directory = Files.createTempDirectory("jphp-numeric-profile-");
        Path authored = directory.resolve("Authored.php"), library = directory.resolve("Library.php"), dump = directory.resolve("Authored.phb");
        Files.writeString(authored, "<?php return 9223372036854775807+1;", StandardCharsets.UTF_8);
        Files.writeString(library, "<?php return 9223372036854775807+1;", StandardCharsets.UTF_8);
        Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
        try {
            AtomicInteger selections = new AtomicInteger();
            env.getModuleManager().setSourceLanguageProfileResolver(path -> PhpLanguageProfile.PHP_8_5);
            env.getModuleManager().setSourceOptimizationProfileResolver(path -> {
                selections.incrementAndGet(); return path.equals(ModuleCacheKey.normalize(authored.toString())) ? FAST : COMPATIBLE;
            });
            ModuleEntity fast = env.getModuleManager().fetchModule(authored.toString(), false);
            assertSame(fast, env.getModuleManager().fetchModule(authored.toString(), false));
            assertSame(FAST, fast.getOptimizationProfile());
            assertSame(COMPATIBLE, env.getModuleManager().fetchTemporaryModule(library.toString(), false).getOptimizationProfile());
            assertEquals(3, selections.get());
            try { env.getModuleManager().setSourceOptimizationProfileResolver(path -> COMPATIBLE); fail("Resolver changed after fetch"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("frozen")); }
            try (OutputStream output = Files.newOutputStream(dump)) { new ModuleDumper(fast.getContext(), env, true).save(fast, output); }
            Environment restored = new Environment(newScope(), OutputStream.nullOutputStream());
            restored.getModuleManager().setSourceOptimizationProfileResolver(path -> { throw new AssertionError("PHB invoked source profile resolver"); });
            try {
                ModuleEntity artifact = restored.getModuleManager().fetchModule(dump.toString(), true);
                assertSame(FAST, artifact.getOptimizationProfile());
                assertEquals(Long.MIN_VALUE, artifact.include(restored).toLong());
            } finally { restored.doFinal(); }
        } finally {
            env.doFinal(); Files.deleteIfExists(dump); Files.deleteIfExists(library); Files.deleteIfExists(authored); Files.deleteIfExists(directory);
        }
    }

    @Test public void phbHeaderRejectsUnknownAndInconsistentProfilesAndReadsOldHeaders() throws Throwable {
        Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
        try {
            ModuleEntity module = new JvmCompiler(env, source("return 1;", null, FAST)).compile(false);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new ModuleDumper(module.getContext(), env, true).save(module, output);
            byte[] bytes = output.toByteArray();
            assertSame(FAST, ModuleDumper.readHeader(new ByteArrayInputStream(bytes)).getOptimizationProfile());
            ByteBuffer.wrap(bytes).putInt(16, 99);
            try { ModuleDumper.readHeader(new ByteArrayInputStream(bytes)); fail("Unknown optimization id accepted"); }
            catch (DumpException expected) { assertTrue(expected.getMessage().contains("99")); }
            ByteBuffer.wrap(bytes).putInt(16, FAST.getId()).putInt(12, PhpLanguageProfile.JPHP_LEGACY.getId());
            try { ModuleDumper.readHeader(new ByteArrayInputStream(bytes)); fail("Fast legacy header accepted"); }
            catch (DumpException expected) { assertTrue(expected.getMessage().contains("PHP_8_5")); }
            output.reset(); DataOutputStream old = new DataOutputStream(output);
            old.writeInt(ModuleDumper.DUMP_STAMP); old.writeInt(ModuleDumper.DUMP_VERSION); old.writeInt(29); old.writeInt(PhpLanguageProfile.PHP_8_5.getId());
            assertSame(COMPATIBLE, ModuleDumper.readHeader(new ByteArrayInputStream(output.toByteArray())).getOptimizationProfile());
        } finally { env.doFinal(); }
    }

    private Memory run(String code, PhpOptimizationProfile profile, boolean dumped) throws Throwable {
        Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
        try { return load(env, code, profile, dumped).include(env); }
        finally { env.doFinal(); }
    }

    private ModuleEntity load(Environment env, String code, PhpOptimizationProfile profile, boolean dumped) throws Throwable {
        Context context = source(code, null, profile);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ModuleDumper dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module); env.scope.addUserModule(module); env.registerModule(module);
        return module;
    }

    private Context source(String code, String name, PhpOptimizationProfile profile) {
        // File/stream contexts begin in inline-HTML mode; unlike Context(String), they require the PHP opener.
        Context context = name == null ? new Context(code) : new Context(new ByteArrayInputStream(("<?php " + code).getBytes(StandardCharsets.UTF_8)), name, StandardCharsets.UTF_8);
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5); context.setPhpOptimizationProfile(profile); return context;
    }
}
