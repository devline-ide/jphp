package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ReferenceMemory;
import java.io.*;
import static org.junit.Assert.*;

/** Generated literal GLOBALS addresses publish actual named roots; no whole-table ARRAY owner. */
public class PhpRuntimeGlobalsCompilerTest extends JvmCompilerCase {
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        var scope=env.getScope();scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);return module;
    }

    @Test public void freshArrayAndCapturedValueOrCellUseExactNamedSlotSourceDump() throws Throwable {
        String[] sources={"$GLOBALS['copy']=[];return 7;","$GLOBALS['GLOBALS']=[];return 7;",
                "$GLOBALS[0]=[];return 7;",
                "$a=[];$f=function()use(&$a){$GLOBALS['copy']=$a;};$f();return 7;",
                "$a=[];$f=function()use(&$a){$GLOBALS['alias']=&$a;};$f();return 7;"};
        for(int route=0;route<sources.length;route++)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,sources[route],dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    for(var closure:module.getClosures()){var invoke=closure.findMethod("__invoke");authority.registerMethod(invoke,closure.getData(),(p,m,bytes)->{});}
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}
                    var table=env.getRuntimeGlobalsTable();assertNull(table.getRuntimeOrigin());
                    assertTrue(env.getSyntheticGlobalsOrigin().isRetired());
                    var dest=route==2?table.getByScalar(php.runtime.memory.LongMemory.valueOf(0))
                            :table.getByScalar(route==1?"GLOBALS":route==4?"alias":"copy");
                    assertNotNull(dest);assertNotNull(dest.getRuntimeHolder());
                    var array=dest.toValue(ArrayMemory.class);assertNotNull(array.getRuntimeOrigin());assertEquals(0,array.size());
                    if(route>=3) {
                        var a=table.getByScalar("a");var cell=(ReferenceMemory)a.getRawValue();
                        assertEquals(route==4?3:2,cell.getRuntimeCell().node().incoming());
                        assertEquals(route==4?1:2,array.getRuntimeOrigin().node().incoming());
                        if(route==4)assertSame(cell,dest.getRawValue());
                        else assertSame(a.toValue(ArrayMemory.class).getStorageIdentity(),array.getStorageIdentity());
                    } else assertEquals(1,array.getRuntimeOrigin().node().incoming());
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }

    @Test public void wholeTableUnknownKeyAndMultiStepRejectBeforeCallerEffects() throws Throwable {
        String[] sources={"echo 'entered';$GLOBALS['copy']=$GLOBALS;return 7;",
                "echo 'entered';$k='copy';$GLOBALS[$k]=[];return 7;",
                "echo 'entered';$GLOBALS['copy'][]=[];return 7;"};
        for(String source:sources)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    try{authority.registerModule(module,module.getData(),(p,m,bytes)->{});fail("Unproven native-table path admitted");}
                    catch(IllegalArgumentException|IOException expected){}
                    assertEquals(0,output.size());assertEquals(0,domain.graph().liveNodes());
                }
            } finally {env.doFinal();}
        }
    }
}
