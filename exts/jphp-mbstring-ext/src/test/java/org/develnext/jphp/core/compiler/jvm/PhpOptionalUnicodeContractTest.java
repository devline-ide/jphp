package org.develnext.jphp.core.compiler.jvm;

import org.junit.*;
import org.develnext.jphp.ext.mbstring.MbstringExtension;
import org.develnext.jphp.ext.intl.IntlExtension;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Optional subset only; raw native oracle bytes, ordinary execution, no managed issuer claims. */
public class PhpOptionalUnicodeContractTest {
    @BeforeClass public static void origins() throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        for (Class<?> type : new Class<?>[]{MbstringExtension.class,IntlExtension.class,com.ibm.icu.lang.UCharacter.class,com.ibm.icu.charset.CharsetProviderICU.class})
            System.out.println("OPTIONAL_ORIGIN " + type.getName() + " " + Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static CompileScope scope(boolean debug, boolean optional) {
        var scope = new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        if (optional) { scope.registerExtension(new MbstringExtension());scope.registerExtension(new IntlExtension()); }
        return scope;
    }
    private static ModuleEntity compile(Environment env, String source, boolean phb, boolean debug, PhpLanguageProfile profile) throws Throwable {
        var context = new Context(source);context.setPhpLanguageProfile(profile);
        var module = new JvmCompiler(env,context).compile(false);
        assertEquals(PhpLanguageProfile.JPHP_LEGACY,env.getScope().getPhpLanguageProfile());assertEquals(profile,module.getPhpLanguageProfile());
        if (phb) {
            var bytes = new ByteArrayOutputStream();var dumper = new ModuleDumper(context,env,debug);dumper.save(module,bytes);
            assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());assertEquals(profile,module.getPhpLanguageProfile());
        }
        var scope = env.getScope();scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static byte[] resource(String name) throws IOException {
        try (var input = PhpOptionalUnicodeContractTest.class.getResourceAsStream("/optional-unicode/" + name)) {
            if (input == null) throw new IOException("Missing optional native fixture: " + name);
            return input.readAllBytes();
        }
    }
    private static void finish(Environment env, Throwable primary) throws Throwable {
        try { env.doFinal(); } catch (Throwable cleanup) { if (primary == null) throw cleanup;else if (cleanup != primary) primary.addSuppressed(cleanup); }
    }
    private static void nativeFixture(String id, int rows) throws Throwable {
        byte[] expected = resource(id + ".stdout");assertEquals(rows,new String(expected,StandardCharsets.UTF_8).lines().count());
        assertEquals(0,resource(id + ".stderr").length);String source = new String(resource(id + ".php"),StandardCharsets.UTF_8);
        assertTrue(source.startsWith("<?php"));source = source.substring(5);
        for (boolean debug : new boolean[]{false,true}) for (boolean phb : new boolean[]{false,true}) {
            var output = new ByteArrayOutputStream();var env = new Environment(scope(debug,true),output);Throwable primary = null;
            try {
                compile(env,source,phb,debug,PhpLanguageProfile.PHP_8_5).include(env);env.flushAll();
                assertArrayEquals(id + " debug=" + debug + " PHB=" + phb,expected,output.toByteArray());
                System.out.println("CASEPASS " + id + " debug=" + debug + " PHB=" + phb + " rows=" + rows);
            } catch (Throwable failure) { primary = failure;throw failure; } finally { finish(env,primary); }
        }
    }
    @Test(timeout=120000) public void bytesUnicodeEncodingAndGraphemeMatchNativeInSourceAndPhb() throws Throwable { nativeFixture("weak",38); }
    @Test(timeout=120000) public void strictArgumentsAndReflectionMatchNativeInSourceAndPhb() throws Throwable { nativeFixture("strict",4);nativeFixture("reflection",7); }
    @Test(timeout=60000) public void providersAreOptionalModuleLocalAndEncodingIsEnvironmentLocal() throws Throwable {
        String exists = "echo function_exists('mb_trim')?'1':'0',function_exists('grapheme_str_split')?'1':'0';";
        for (boolean debug : new boolean[]{false,true}) for (boolean phb : new boolean[]{false,true}) {
            var absentOut = new ByteArrayOutputStream();var absent = new Environment(scope(debug,false),absentOut);Throwable primary = null;
            try { compile(absent,exists,phb,debug,PhpLanguageProfile.PHP_8_5).include(absent);absent.flushAll();assertEquals("00",absentOut.toString(StandardCharsets.UTF_8)); }
            catch (Throwable failure) { primary = failure;throw failure; } finally { finish(absent,primary); }
            var shared = scope(debug,true);var outA = new ByteArrayOutputStream();var outB = new ByteArrayOutputStream();
            var a = new Environment(shared,outA);var b = new Environment(shared,outB);primary = null;
            try {
                for (var profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5,PhpLanguageProfile.JPHP_LEGACY}) {
                    compile(a,exists,phb,debug,profile).include(a);a.echo("|");
                }
                compile(a,"mb_internal_encoding('latin1');echo mb_internal_encoding();",phb,debug,PhpLanguageProfile.PHP_8_5).include(a);
                compile(b,"echo mb_internal_encoding();",phb,debug,PhpLanguageProfile.PHP_8_5).include(b);
                a.flushAll();b.flushAll();assertEquals("00|11|00|ISO-8859-1",outA.toString(StandardCharsets.UTF_8));assertEquals("UTF-8",outB.toString(StandardCharsets.UTF_8));
            } catch (Throwable failure) { primary = failure;throw failure; }
            finally { try { finish(a,primary); } finally { finish(b,primary); } }
        }
    }
}
