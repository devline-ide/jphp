package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

/** Top-level declarations exercise the ordinary ASM trait copy route, with no conditional install wrapper. */
public class PhpOrdinaryTraitStaticAliasTest extends JvmCompilerCase {
    private void runGroup(String group,int expectedRows) throws Throwable {
        InputStream input=getClass().getResourceAsStream("/functions/php_ordinary_trait_static_alias.tsv");
        if(input==null)input=getClass().getResourceAsStream("/resources/functions/php_ordinary_trait_static_alias.tsv");
        assertNotNull(input);int count=0;
        try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            for(String line;(line=reader.readLine())!=null;) {
                String[] cells=line.split("\t",-1);assertEquals(5,cells.length);if(!group.equals(cells[0]))continue;count++;
                String source=new String(Base64.getDecoder().decode(cells[3]),StandardCharsets.UTF_8);
                String expected=new String(Base64.getDecoder().decode(cells[4]),StandardCharsets.UTF_8);
                assertFalse("Bare Context must not contain an opening tag",source.startsWith("<?php"));
                for(PhpLanguageProfile profile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5})
                    for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
                        var scope=super.newScope();scope.setPhpLanguageProfile(profile);scope.setDebugMode(debug);
                        scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
                        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
                        try {
                            var context=new Context(source);context.setPhpLanguageProfile(profile);
                            var module=new JvmCompiler(env,context).compile(false);
                            if(phb) {
                                var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
                                dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                            }
                            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.doFinal();
                            assertEquals(cells[1]+" profile="+profile+" strict="+cells[2]+" debug="+debug+" PHB="+phb,
                                    expected,output.toString(StandardCharsets.UTF_8));
                        }finally{try{env.doFinal();}finally{scope.getClassLoader().close();}}
                    }
            }
        }
        assertEquals(expectedRows,count);
    }
    @Test(timeout=120000) public void ordinaryAliasesKeepIndependentArbitraryInitializerStorage() throws Throwable {runGroup("initializers",12);}
    @Test(timeout=60000) public void ordinaryAliasInheritanceAndTraitReuseKeepDeclaredStorageIdentity() throws Throwable {runGroup("inheritance",2);}
}
