package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Same actual unset fixture under a legacy declaration/caller; this is a JPHP legacy contract. */
public class PhpStaticUnsetLegacyProfileTest {
    private static byte[] resource(String path) throws Exception {
        try(InputStream in=PhpStaticUnsetLegacyProfileTest.class.getClassLoader().getResourceAsStream(path)){
            assertNotNull(path,in);return in.readAllBytes();
        }
    }
    @Test public void legacyUnsetRetainsStrictLevelAcrossScopeDefaultsSourceAndPhb() throws Throwable {
        String source=new String(resource("resources/static-unset-profile/legacy.php"),StandardCharsets.UTF_8);
        assertTrue(source.startsWith("<?php"));source=source.substring(5);
        byte[] expected=resource("resources/static-unset-profile/legacy.stdout");int cells=0;
        for(PhpLanguageProfile scopeProfile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            cells++;var out=new ByteArrayOutputStream();var scope=new CompileScope();scope.setPhpLanguageProfile(scopeProfile);scope.setDebugMode(debug);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var env=new Environment(scope,out);
            try{
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                var module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();var dump=new ModuleDumper(context,env,debug);dump.save(module,bytes);module=dump.load(new ByteArrayInputStream(bytes.toByteArray()));}
                assertEquals(PhpLanguageProfile.JPHP_LEGACY,module.getPhpLanguageProfile());
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("scope="+scopeProfile+" debug="+debug+" phb="+phb,expected,out.toByteArray());
            }finally{env.doFinal();}
        }
        assertEquals(8,cells);
    }
}
