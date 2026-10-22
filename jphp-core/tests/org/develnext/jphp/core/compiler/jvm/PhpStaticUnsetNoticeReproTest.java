package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Independent native-pinned diagnostic repro; source-only stage, no production patch. */
public class PhpStaticUnsetNoticeReproTest {
    @BeforeClass public static void exactOrigins() throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static byte[] resource(String suffix)throws Exception {
        String name="resources/native-oracle/modern-error-diagnostics/strict-static-property-unset"+suffix;
        try(InputStream in=PhpStaticUnsetNoticeReproTest.class.getClassLoader().getResourceAsStream(name)){
            assertNotNull(name,in);return in.readAllBytes();
        }
    }
    @Test(timeout=20000) public void modernStaticPropertyUnsetUsesNoticeAndPreservesStaticSlot()throws Throwable {
        String source=new String(resource(".php"),StandardCharsets.UTF_8);assertTrue(source.startsWith("<?php"));source=source.substring(5);
        byte[] expected=resource(".stdout");var failures=new ArrayList<String>();int cells=0;
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            cells++;var out=new ByteArrayOutputStream();var scope=new CompileScope();scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.setDebugMode(debug);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var env=new Environment(scope,out);
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();var dump=new ModuleDumper(context,env,debug);dump.save(module,bytes);module=dump.load(new ByteArrayInputStream(bytes.toByteArray()));}
                assertEquals(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("debug="+debug+" phb="+phb,expected,out.toByteArray());
            } catch(Throwable failure) {failures.add("debug="+debug+" phb="+phb+": "+failure);}
            finally {env.doFinal();}
        }
        assertEquals(4,cells);assertTrue(String.join("\n",failures),failures.isEmpty());
    }
}
