package org.develnext.jphp.core.compiler.jvm;

import org.junit.*;
import org.develnext.jphp.pcre.PcreExtension;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Raw PHP 8.5 oracle bytes; no implementation-derived match expectations. */
public class PhpPregSplitQuoteContractTest {
    private static Path fixtures;

    @BeforeClass public static void inputs() throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(), origin(Environment.class));
        assertEquals(PhpPortableTestInputs.coreOrigin(), origin(JvmCompiler.class));
        for (Class<?> type : new Class<?>[]{php.runtime.Memory.class, JvmCompiler.class, PcreExtension.class})
            System.out.println("PREG_SPLIT_QUOTE_PROVIDER_ORIGIN " + type.getName() + " " + origin(type));
        fixtures = PhpPortableTestInputs.fixture("qa.preg.split.quote", "preg-split-quote");
    }

    private static Path origin(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    }

    @AfterClass public static void releaseInputs() throws Exception {
        PhpPortableTestInputs.release(fixtures);
        fixtures = null;
    }

    private static CompileScope scope(boolean debug, PcreExtension pcre) {
        var scope = new CompileScope();
        scope.setDebugMode(debug);
        scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new php.runtime.ext.CoreExtension());
        scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        scope.registerExtension(pcre);
        return scope;
    }

    private static void include(Environment env, String file, boolean phb, boolean debug) throws Throwable {
        var context = new Context(Files.readString(fixtures.resolve(file), StandardCharsets.UTF_8), fixtures.resolve(file).toFile());
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(false);
        if (phb) {
            var out = new ByteArrayOutputStream();
            var dump = new ModuleDumper(context, env, debug);
            dump.save(module, out);
            module = dump.load(new ByteArrayInputStream(out.toByteArray()));
        }
        env.scope.loadModule(module);
        env.scope.addUserModule(module);
        env.registerModule(module);
        module.include(env);
        assertEquals(PhpLanguageProfile.JPHP_LEGACY, env.scope.getPhpLanguageProfile());
    }

    private static void finish(Environment env, Throwable primary) throws Throwable {
        try { env.doFinal(); }
        catch (Throwable cleanup) {
            if (primary == null) throw cleanup;
            if (primary != cleanup) primary.addSuppressed(cleanup);
        }
    }

    private static String body(String text, int rows) {
        assertTrue("Missing PHP oracle engine envelope", text.startsWith("engine:[\""));
        int newline = text.indexOf('\n');
        assertTrue(newline > 0);
        String result = text.substring(newline + 1);
        assertEquals("Exact semantic oracle row count", rows, result.lines().count());
        var ids = new HashSet<String>();
        for (String row : result.split("\n")) {
            int colon = row.indexOf(':');
            assertTrue("Malformed semantic row: " + row, colon > 0);
            assertTrue("Duplicate semantic row: " + row, ids.add(row.substring(0, colon)));
        }
        return result;
    }

    private static void compare(String file, int rows) throws Throwable {
        String expected = Files.readString(fixtures.resolve(file + ".stdout"), StandardCharsets.UTF_8);
        assertTrue("Expected actual PHP8.5.11 output", expected.startsWith("engine:[\"8.5.11\",\"10.44 2024-06-07\",true,"));
        assertEquals("Oracle stderr must stay empty", 0, Files.size(fixtures.resolve(file + ".stderr")));
        byte[] expectedBody = body(expected, rows).getBytes(StandardCharsets.UTF_8);
        assertNull("Packaged provider required; no QA native override", System.getProperty("qa.pcre.native.path"));
        assertNull("Packaged provider required; no QA native override", System.getProperty("qa.pcre.native.sha256"));
        for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            var pcre = new PcreExtension();
            var out = new ByteArrayOutputStream();
            var env = new Environment(scope(debug, pcre), out);
            Throwable primary = null;
            try {
                include(env, file + ".php", phb, debug);
                env.flushAll();
                String actual = out.toString(StandardCharsets.UTF_8);
                String engine = actual.substring(0, actual.indexOf('\n'));
                assertTrue("Truthful candidate engine envelope: " + engine,
                        engine.endsWith("\",\"" + pcre.nativeVersion() + "\"," + pcre.nativeJitSupported() + ",\"1000000\",\"100000\"]"));
                assertArrayEquals(file + " debug=" + debug + " PHB=" + phb,
                        expectedBody, body(actual, rows).getBytes(StandardCharsets.UTF_8));
                System.out.println("PREG_SPLIT_QUOTE_ENGINE_ACTUAL fixture=" + file + " debug=" + debug + " PHB=" + phb + " " + engine);
                System.out.println("PREG_SPLIT_QUOTE_BYTES fixture=" + file + " rows=" + rows + " debug=" + debug + " PHB=" + phb + " " + Base64.getEncoder().encodeToString(out.toByteArray()));
            } catch (Throwable failure) { primary = failure; throw failure; }
            finally { finish(env, primary); }
        }
    }

    @Test(timeout=180000) public void splitNative84RowsSourceAndDump() throws Throwable { compare("split", 84); }
    @Test(timeout=180000) public void splitStrictNative18RowsSourceAndDump() throws Throwable { compare("split-strict", 18); }
    @Test(timeout=180000) public void quoteNative35RowsSourceAndDump() throws Throwable { compare("quote", 35); }
    @Test(timeout=180000) public void quoteStrictNative13RowsSourceAndDump() throws Throwable { compare("quote-strict", 13); }
}
