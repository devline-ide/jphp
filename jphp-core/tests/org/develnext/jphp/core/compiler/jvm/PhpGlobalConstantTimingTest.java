package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.exception.BaseBaseException;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Portable source/actual-PHB controls; the corpus is pinned against PHP 8.5 -n. */
public class PhpGlobalConstantTimingTest {
    private static String execute(String source, PhpLanguageProfile moduleProfile,
                                  PhpLanguageProfile scopeProfile, boolean debug, boolean phb) throws Throwable {
        var scope = new CompileScope();
        scope.setPhpLanguageProfile(scopeProfile);
        scope.setDebugMode(debug);
        scope.registerExtension(new php.runtime.ext.CoreExtension());
        scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        var out = new ByteArrayOutputStream();
        var env = new Environment(scope, out);
        try {
            var context = new Context(source);
            context.setPhpLanguageProfile(moduleProfile);
            var module = new JvmCompiler(env, context).compile(false);
            if (phb) {
                var bytes = new ByteArrayOutputStream();
                var dumper = new ModuleDumper(context, env, true);
                dumper.save(module, bytes);
                module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                assertTrue("actual PHB bytes", bytes.size() > 100);
            }
            assertEquals(moduleProfile, module.getPhpLanguageProfile());
            if (moduleProfile == PhpLanguageProfile.PHP_8_5) {
                assertTrue("modern globals must not auto-publish", module.getConstants().isEmpty());
            }
            scope.loadModule(module);
            scope.addUserModule(module);
            env.registerModule(module);
            try { module.include(env); }
            catch (BaseBaseException failure) {
                env.flushAll();
                out.write(("@throw:" + failure.getReflection().getName() + ":"
                        + failure.getMessage(env).toString()).getBytes(StandardCharsets.UTF_8));
            }
            env.flushAll();
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            try { env.doFinal(); } finally { scope.getClassLoader().close(); }
        }
    }

    @Test(timeout=120000) public void php85NativeCorpusSourceAndActualPhb() throws Throwable {
        InputStream input;
        String external = System.getProperty("devline.gct.corpus");
        if (external == null) input = getClass().getResourceAsStream("/resources/functions/php85_global_constant_timing.tsv");
        else input = Files.newInputStream(Path.of(external));
        assertNotNull("Install corpus resource or set devline.gct.corpus", input);
        var failures = new ArrayList<String>();
        int rows = 0, variants = 0;
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                rows++;
                var cells = line.split("\t", -1);
                assertEquals(3, cells.length);
                String source = new String(Base64.getDecoder().decode(cells[1]), StandardCharsets.UTF_8);
                String expected = new String(Base64.getDecoder().decode(cells[2]), StandardCharsets.UTF_8);
                for (var scopeProfile : PhpLanguageProfile.values())
                    for (boolean debug : new boolean[]{false, true})
                        for (boolean phb : new boolean[]{false, true}) {
                            variants++;
                            String label = cells[0]+" scope="+scopeProfile+" debug="+debug+" PHB="+phb;
                            try { assertEquals(label, expected,
                                    execute(source, PhpLanguageProfile.PHP_8_5, scopeProfile, debug, phb)); }
                            catch (Throwable failure) { failures.add(label+": "+failure); }
                        }
            }
        }
        assertEquals(28, rows);
        assertEquals(224, variants);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test(timeout=120000) public void legacyPublicationAndFoldingRemainUnchanged() throws Throwable {
        String[][] controls = {
            {"global-forward", "echo GCT_LEGACY,':';const GCT_LEGACY=29;echo GCT_LEGACY;", "29:29"},
            {"namespace-forward", "namespace GCT;echo E_ALL,':',\\E_ALL;const E_ALL=7;echo ':',E_ALL,':',\\E_ALL;", "7:32767:7:32767"},
            {"function-forward", "function gctLegacy(){return GCT_LEGACY;}echo gctLegacy(),':';const GCT_LEGACY=30;echo gctLegacy();", "30:30"},
            {"unreachable", "echo defined('GCT_LEGACY')?'published':'absent';return;const GCT_LEGACY=31;", "published"}
        };
        var failures = new ArrayList<String>();
        for (String[] row : controls) for (var scopeProfile : PhpLanguageProfile.values())
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                String label = row[0]+" scope="+scopeProfile+" debug="+debug+" PHB="+phb;
                try { assertEquals(label, row[2], execute(row[1], PhpLanguageProfile.JPHP_LEGACY, scopeProfile, debug, phb)); }
                catch (Throwable failure) { failures.add(label+": "+failure); }
            }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test(timeout=120000) public void sameCompiledModuleCachesStayEnvironmentLocal() throws Throwable {
        String source="namespace GCT;function read(){return GCT_CACHE_VALUE;}echo read(),':';";
        var failures = new ArrayList<String>();
        for (var scopeProfile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false,true})
            for (boolean phb : new boolean[]{false,true}) for (boolean localFirst : new boolean[]{false,true}) {
                var scope = new CompileScope();scope.setPhpLanguageProfile(scopeProfile);scope.setDebugMode(debug);
                scope.registerExtension(new php.runtime.ext.CoreExtension());
                scope.registerExtension(new php.runtime.ext.SPLExtension());
                scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                var firstOut=new ByteArrayOutputStream();var secondOut=new ByteArrayOutputStream();
                var first=new Environment(scope,firstOut);var second=new Environment(scope,secondOut);
                String label="shared scope="+scopeProfile+" debug="+debug+" PHB="+phb+" localFirst="+localFirst;
                try {
                    var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module=new JvmCompiler(first,context).compile(false);
                    if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,first,true);
                        dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);
                    first.defineConstant("GCT_CACHE_VALUE",php.runtime.memory.LongMemory.valueOf(10),true);
                    second.defineConstant("GCT_CACHE_VALUE",php.runtime.memory.LongMemory.valueOf(10),true);
                    second.defineConstant("GCT\\GCT_CACHE_VALUE",php.runtime.memory.LongMemory.valueOf(20),true);
                    first.registerModule(module);second.registerModule(module);
                    if(localFirst){module.include(second);module.include(first);}
                    else{module.include(first);module.include(second);}
                    first.defineConstant("GCT\\GCT_CACHE_VALUE",php.runtime.memory.LongMemory.valueOf(30),true);
                    module.include(first);first.flushAll();second.flushAll();
                    assertEquals(label+" first","10:10:",firstOut.toString(StandardCharsets.UTF_8));
                    assertEquals(label+" second","20:",secondOut.toString(StandardCharsets.UTF_8));
                } catch(Throwable failure) {failures.add(label+": "+failure);}
                finally {try{first.doFinal();}finally{try{second.doFinal();}finally{scope.getClassLoader().close();}}}
            }
        assertTrue(String.join("\n",failures),failures.isEmpty());
    }
}
