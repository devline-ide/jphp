package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.LongMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Observable retained resolution entries, with actual emitted sites and source/PHB execution. */
public class PhpConstantResolutionCacheTest {
    private static final String PREFIX = "\0jphp.constant.fetch\0";
    private static final class InspectEnvironment extends Environment {
        InspectEnvironment(CompileScope scope, ByteArrayOutputStream out) { super(scope, out); }
        long resolutionEntries() { return statics.keySet().stream().filter(key -> key.startsWith(PREFIX)).count(); }
    }
    private static CompileScope scope(PhpLanguageProfile profile, boolean debug) {
        var scope = new CompileScope();
        scope.setPhpLanguageProfile(profile);
        scope.setDebugMode(debug);
        scope.registerExtension(new php.runtime.ext.CoreExtension());
        scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return scope;
    }
    private static String resource(String path) throws IOException {
        try (var input = PhpConstantResolutionCacheTest.class.getResourceAsStream(path)) {
            assertNotNull(path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static int modernFetches(Collection<byte[]> classes) {
        int[] count = {0};
        Set<String> visited = new HashSet<>();
        for (byte[] bytes : classes) {
            assertNotNull("actual generated class", bytes);
            var reader = new ClassReader(bytes);
            if (!visited.add(reader.getClassName())) continue;
            reader.accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                               String descriptor, boolean isInterface) {
                            if (owner.equals(php.runtime.PhpConstantReads.OWNER) && name.equals("fetch")) {
                                assertEquals("typed emitted helper opcode", Opcodes.INVOKESTATIC, opcode);
                                assertFalse("typed helper is a class static member", isInterface);
                                assertEquals("typed emitted helper", php.runtime.PhpConstantReads.DESCRIPTOR, descriptor);
                                count[0]++;
                            } else if (owner.equals("php/runtime/env/Environment") && name.equals("__getConstant")) {
                                assertEquals("modern emitted helper opcode", Opcodes.INVOKEVIRTUAL, opcode);
                                assertEquals("modern emitted helper", "(Ljava/lang/String;Ljava/lang/String;Lphp/runtime/env/TraceInfo;ZLjava/lang/String;)Lphp/runtime/Memory;", descriptor);
                                count[0]++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return count[0];
    }
    private static void execute(String source, String expected, PhpLanguageProfile scopeProfile,
                                boolean debug, boolean phb, int expectedSites, long expectedEntries) throws Throwable {
        var scope = scope(scopeProfile, debug);
        var output = new ByteArrayOutputStream();
        var env = new InspectEnvironment(scope, output);
        try {
            var context = new Context(source.startsWith("<?php") ? source.substring(5) : source);
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module = new JvmCompiler(env, context).compile(false);
            if (phb) {
                var bytes = new ByteArrayOutputStream();
                var dumper = new ModuleDumper(context, env, true);
                dumper.save(module, bytes);
                assertTrue("actual PHB", bytes.size() > 100);
                module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            List<byte[]> classes = new ArrayList<>();
            classes.add(module.getData());
            for (var function : module.getFunctions()) classes.add(function.getData());
            for (var closure : module.getClosures()) classes.add(closure.getData());
            int sites = modernFetches(classes);
            if (expectedSites >= 0) assertEquals("actual emitted fetch sites", expectedSites, sites);
            assertEquals("before include", 0, env.resolutionEntries());
            scope.loadModule(module);
            scope.addUserModule(module);
            env.registerModule(module);
            module.include(env);
            env.flushAll();
            assertEquals("native output", expected, output.toString(StandardCharsets.UTF_8));
            if (expectedEntries >= 0) assertEquals("retained resolution names", expectedEntries, env.resolutionEntries());
            System.out.println("constant-cache-observation scope=" + scopeProfile + " debug=" + debug
                    + " PHB=" + phb + " fetchSites=" + sites + " resolutionEntries=" + env.resolutionEntries());
        } finally {
            try { env.doFinal(); } finally { scope.getClassLoader().close(); }
        }
    }
    private static void tenThousandSites(String family, long expectedEntries) throws Throwable {
        String source = resource("/resources/functions/constant-resolution-cache/" + family + "-10000.php");
        for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true})
            execute(source, "70000|70000", PhpLanguageProfile.PHP_8_5, debug, phb, 10000, expectedEntries);
    }
    @Test(timeout=120000) public void globalTenThousandSitesRetainNoResolutionNamesSourceAndRealPhb() throws Throwable {
        tenThousandSites("global", 0);
    }
    @Test(timeout=120000) public void qualifiedTenThousandSitesRetainNoResolutionNamesSourceAndRealPhb() throws Throwable {
        tenThousandSites("qualified", 0);
    }
    @Test(timeout=120000) public void namespaceFallbackTenThousandSitesRetainChoicesAfterDefineSourceAndRealPhb() throws Throwable {
        tenThousandSites("fallback", 10000);
    }
    @Test public void nativeCaseSensitiveFixedTargetsAndFailedSiteRetrySourceAndRealPhb() throws Throwable {
        int rows = 0, comparisons = 0;
        try (var reader = new BufferedReader(new StringReader(resource("/resources/functions/php85_constant_resolution_cache.tsv")))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isEmpty()) continue;
                String[] row = line.split("\t", -1);
                assertEquals(3, row.length);
                rows++;
                String source = new String(Base64.getDecoder().decode(row[1]), StandardCharsets.UTF_8);
                String expected = new String(Base64.getDecoder().decode(row[2]), StandardCharsets.UTF_8);
                for (var profile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false, true})
                    for (boolean phb : new boolean[]{false, true}) {
                        comparisons++;
                        execute(source, expected, profile, debug, phb, -1, -1);
                    }
            }
        }
        assertEquals(4, rows);
        assertEquals(32, comparisons);
    }
    @Test public void oldThreeArgumentHelperKeepsItsLookupAndDoesNotWriteResolutionEntries() throws Throwable {
        for (var profile : PhpLanguageProfile.values()) {
            var scope = scope(profile, false);
            var env = new InspectEnvironment(scope, new ByteArrayOutputStream());
            try {
                assertTrue(env.defineConstant("CACHE_OLD_HELPER", LongMemory.valueOf(42), true));
                Memory actual = env.__getConstant("Scope\\CACHE_OLD_HELPER", "scope\\cache_old_helper", TraceInfo.UNKNOWN);
                assertEquals(42, actual.toLong());
                assertEquals(0, env.resolutionEntries());
            } finally {
                try { env.doFinal(); } finally { scope.getClassLoader().close(); }
            }
        }
    }
}
