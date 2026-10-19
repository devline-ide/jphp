package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
public class PhpAutoloadFailureRecoveryTest {
    private static final String SOURCE="<?php\n$count=0;spl_autoload_register(function($name)use(&$count){$count++;if($count===1)throw new RuntimeException('first');});try{class_exists('RecoveredClass');}catch(RuntimeException $e){echo 'caught:';}echo class_exists('RecoveredClass')?'ready:':'missing:',$count;";
    @Test public void throwingAutoloadReleasesRecursionLockInBothLanguageProfiles()throws Throwable {
        var failures=new ArrayList<String>();int variants=0;
        for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            variants++;var out=new ByteArrayOutputStream();var scope=new CompileScope();scope.setPhpLanguageProfile(profile);scope.setDebugMode(debug);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
            var env=new Environment(scope,out);
            try {
                var context=new Context(SOURCE,new File("autoload-recovery.php"));context.setPhpLanguageProfile(profile);var module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertEquals("caught:missing:2",out.toString(StandardCharsets.UTF_8));
            }catch(Throwable failure){failures.add(profile+"/"+debug+"/"+phb+": "+failure);}
            finally{env.doFinal();}
        }
        assertEquals(8,variants);assertTrue(String.join("\n",failures),failures.isEmpty());
    }
}
