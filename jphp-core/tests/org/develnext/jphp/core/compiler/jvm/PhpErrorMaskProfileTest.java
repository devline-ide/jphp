package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class PhpErrorMaskProfileTest {
    private static Environment environment(ByteArrayOutputStream out,PhpLanguageProfile profile) {
        var scope=new CompileScope();scope.setPhpLanguageProfile(profile);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope,out);
    }
    private static void execute(Environment env,PhpLanguageProfile profile,String source,boolean debug,boolean phb)throws Throwable {
        source=source.replace("CATEGORY",profile==PhpLanguageProfile.PHP_8_5?"Core":"core");
        var context=new Context(source);context.setPhpLanguageProfile(profile);
        env.scope.setDebugMode(debug);
        var module=new JvmCompiler(env,context).compile(false);
        if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
        assertEquals(profile,module.getPhpLanguageProfile());
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
    }
    private static final String[][] NATIVE_CASES={
        {"constant-expressions","const MASK=E_ALL;class Masks{const VALUE=E_ALL;public $mask=E_ALL;}function defaultMask($mask=E_ALL){return $mask;}echo E_ALL,':',MASK,':',Masks::VALUE,':',(new Masks)->mask,':',defaultMask();","30719:30719:30719:30719:30719"},
        {"runtime-and-catalogs","echo constant('E_ALL'),':',get_defined_constants()['E_ALL'],':',get_defined_constants(true)['Core']['E_ALL'],':',(new ReflectionExtension('Core'))->getConstants()['E_ALL'],':',(defined('E_ALL')?'yes':'no');","30719:30719:30719:30719:yes"},
        {"explicit-numeric-masks","foreach([2048,32767,-1,30719,0]as$mask){error_reporting($mask);echo error_reporting(),':';}","2048:32767:-1:30719:0:"},
        {"namespace-fallback-only","namespace MaskProbe;echo E_ALL,':',\\E_ALL;","30719:30719"},
        {"namespace-local-override","namespace MaskProbe;const E_ALL=7;echo E_ALL,':',\\E_ALL;","7:30719"}
    };
    @Test public void modernConstantsAndNumericMasksMatchNativeSourcePhb()throws Throwable {
        var failures=new ArrayList<String>();int count=0;
        for(String[] row:NATIVE_CASES)for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            count++;var out=new ByteArrayOutputStream();var env=environment(out,PhpLanguageProfile.JPHP_LEGACY);
            try {
                execute(env,PhpLanguageProfile.PHP_8_5,row[1],debug,phb);env.flushAll();
                assertEquals(row[0],row[2],out.toString(StandardCharsets.UTF_8));
            }catch(Throwable failure){failures.add(row[0]+"/"+debug+"/"+phb+": "+failure);}
            finally{env.doFinal();}
        }
        assertEquals(20,count);assertTrue(String.join("\n",failures),failures.isEmpty());
    }
    private static final String VALUES="echo E_ALL,':',constant('E_ALL'),':',get_defined_constants()['E_ALL'],':',get_defined_constants(true)['CATEGORY']['E_ALL'];$e=new ReflectionExtension('Core');$c=$e->getConstants();echo ':',$c['E_ALL'],';';";
    @Test public void legacyConstantsRetainTheirMaskWithEitherScopeProfile()throws Throwable {
        for(var scopeProfile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();var env=environment(out,scopeProfile);
            try {
                execute(env,PhpLanguageProfile.JPHP_LEGACY,VALUES,debug,phb);env.flushAll();
                assertEquals(scopeProfile+"/"+debug+"/"+phb,"32767:32767:32767:32767:32767;",out.toString(StandardCharsets.UTF_8));
            }finally{env.doFinal();}
        }
    }
    @Test public void modernAndLegacyModulesDoNotLeakConstantValuesAcrossCalls()throws Throwable {
        for(var first:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var second=first==PhpLanguageProfile.PHP_8_5?PhpLanguageProfile.JPHP_LEGACY:PhpLanguageProfile.PHP_8_5;
            var out=new ByteArrayOutputStream();var env=environment(out,second);
            try {
                execute(env,first,VALUES,debug,phb);
                env.scope.setPhpLanguageProfile(first);execute(env,second,VALUES,debug,phb);
                env.scope.setPhpLanguageProfile(second);execute(env,first,VALUES,debug,phb);env.flushAll();
                String a=first==PhpLanguageProfile.PHP_8_5?"30719":"32767",b=second==PhpLanguageProfile.PHP_8_5?"30719":"32767";
                String expected=String.join(":",Collections.nCopies(5,a))+";"+String.join(":",Collections.nCopies(5,b))+";"+String.join(":",Collections.nCopies(5,a))+";";
                assertEquals(first+"/"+debug+"/"+phb,expected,out.toString(StandardCharsets.UTF_8));
            }finally{env.doFinal();}
        }
    }
}
