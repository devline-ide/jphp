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

/** Exact named global READ/REF results retain their semantic VALUE/CELL through real consumers. */
public class PhpRuntimeGlobalsLifecycleCompilerTest extends JvmCompilerCase {
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        var scope=env.getScope();scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);return module;
    }

    @Test public void literalReadAndReferenceResultsSourceDump() throws Throwable {
        String[] sources={"$GLOBALS['x']=[];return $GLOBALS['x'];",
                "$GLOBALS['x']=[];$r=$GLOBALS['x'];return $r;",
                "$GLOBALS['x']=[];$r=&$GLOBALS['x'];return 7;",
                "$r=&$GLOBALS['missing'];return 7;"};
        for(int route=0;route<sources.length;route++)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,sources[route],dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)) {
                        if(route<2){assertTrue(result.raw().toValue() instanceof ArrayMemory);assertEquals(0,result.raw().toValue(ArrayMemory.class).size());}
                        else assertEquals(7,result.raw().toLong());
                        var table=env.getRuntimeGlobalsTable();assertNull(table.getRuntimeOrigin());
                        var entry=table.getByScalar(route==3?"missing":"x");assertNotNull(entry);
                        if(route>=2){var cell=(ReferenceMemory)entry.getRawValue();assertNotNull(cell.getRuntimeCell());assertEquals(2,cell.getRuntimeCell().node().incoming());assertSame(cell,table.getByScalar("r").getRawValue());if(route==3)assertTrue(cell.getRuntimeCell().payload().raw().isNull());}
                        else assertNotNull(entry.toValue(ArrayMemory.class).getRuntimeOrigin());
                    }
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }

    @Test public void missingReadCapturesNullBeforeHandlerCreatesNamedSlotSourceDump() throws Throwable {
        String source="$v=7;set_error_handler(function($n,$s)use($v){$GLOBALS['x']=$v;return true;});$r=$GLOBALS['x'];return $r;";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    for(var member:php.runtime.ext.core.LangFunctions.class.getDeclaredMethods())if(member.getName().equals("set_error_handler"))
                        authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                            public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                            public void validateArguments(java.util.List<PhpRuntimeOwnership.Value> values){}
                        });
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,bytes)->{});
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)){assertTrue("READ must return captured NULL",result.raw().isNull());}
                    assertEquals(7,env.getRuntimeGlobalsTable().getByScalar("x").toLong());
                    env.releaseOwnedErrorHandlers();
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }

    @Test public void literalIntegerWriteCreatesRealProducerSourceDump() throws Throwable {
        String[] sources={"$GLOBALS['x']=1;return $GLOBALS['x'];", "$GLOBALS[0]=7;return $GLOBALS[0];",
                "$GLOBALS['GLOBALS']=9;return $GLOBALS['GLOBALS'];"};
        for(int route=0;route<sources.length;route++)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,sources[route],dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)){assertEquals(route==0?1:route==1?7:9,result.raw().toLong());}
                    assertNull(env.getRuntimeGlobalsTable().getRuntimeOrigin());
                    assertTrue(env.getSyntheticGlobalsOrigin().isRetired());
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }

    @Test public void originalUnlinkRecreateKeepsOldAliasAndValueCopySourceDump() throws Throwable {
        String[] sources={"$GLOBALS['x']=1;$r=&$GLOBALS['x'];unset($GLOBALS['x']);$GLOBALS['x']=2;return $r;",
                "$GLOBALS['x']=1;$r=&$GLOBALS['x'];unset($GLOBALS['x']);$x=3;return $r;",
                "$GLOBALS[0]=7;$r=&$GLOBALS['0'];unset($GLOBALS[0]);return $r;",
                "$GLOBALS['GLOBALS']=9;$r=&$GLOBALS['GLOBALS'];unset($GLOBALS['GLOBALS']);return $r;",
                "$GLOBALS['x']=[];$a=$GLOBALS['x'];unset($GLOBALS['x']);$a[]=[];$GLOBALS['x']=[];return $a;"};
        for(int route=0;route<sources.length;route++)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,sources[route],dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)) {
                        var table=env.getRuntimeGlobalsTable();assertNull(table.getRuntimeOrigin());
                        if(route==4){
                            var old=result.raw().toValue(ArrayMemory.class);var fresh=table.getByScalar("x").toValue(ArrayMemory.class);
                            assertEquals(1,old.size());assertEquals(0,fresh.size());assertNotSame(old.getStorageIdentity(),fresh.getStorageIdentity());
                            assertEquals(2,old.getRuntimeOrigin().node().incoming());assertEquals(1,fresh.getRuntimeOrigin().node().incoming());
                        } else {
                            assertEquals(route<2?1:route==2?7:9,result.raw().toLong());
                            var cell=(ReferenceMemory)table.getByScalar("r").getRawValue();assertEquals(1,cell.getRuntimeCell().node().incoming());
                            if(route<2){assertEquals(route==0?2:3,table.getByScalar("x").toLong());assertNotSame(cell,table.getByScalar("x").getRawValue());}
                            else assertNull(table.getByScalar(route==2?php.runtime.memory.LongMemory.valueOf(0):"GLOBALS"));
                        }
                    }
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }

    @Test public void keyedReadTransfersActualValueToNativeSendSourceDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,"$GLOBALS['x']=[];return count($GLOBALS['x']);",dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    var member=php.runtime.ext.core.ArrayFunctions.class.getMethod("count",Environment.class,TraceInfo.class,Memory.class);
                    authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                        public void validateArguments(java.util.List<PhpRuntimeOwnership.Value> values){assertTrue(values.getFirst().raw() instanceof ArrayMemory);}
                    });
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)){assertEquals(0,result.raw().toLong());}
                    var origin=env.getRuntimeGlobalsTable().getByScalar("x").toValue(ArrayMemory.class).getRuntimeOrigin();
                    assertEquals("Only the real named slot survives native SEND",1,origin.node().incoming());
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {env.doFinal();}
        }
    }

    @Test public void unknownReadAndWholeTableReferenceRejectBeforeEffects() throws Throwable {
        String[] sources={"echo 'entered';$key='x';return $GLOBALS[$key];",
                "echo 'entered';return $GLOBALS['x']['y'];",
                "echo 'entered';$r=&$GLOBALS;return 7;",
                "echo 'entered';serialize($GLOBALS);return 7;",
                "echo 'entered';$f=function(){return $GLOBALS;};return 7;"};
        for(String source:sources)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    var member=php.runtime.ext.core.StringFunctions.class.getMethod("serialize",Environment.class,TraceInfo.class,Memory.class);
                    authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                        public void validateArguments(java.util.List<PhpRuntimeOwnership.Value> values){}
                    });
                    try{
                        for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,bytes)->{});
                        authority.registerModule(module,module.getData(),(p,m,bytes)->{});fail("Unproven native-table READ admitted");}
                    catch(IllegalArgumentException|IOException expected){}
                    assertEquals(0,output.size());assertEquals(0,domain.graph().liveNodes());
                }
            } finally {env.doFinal();}
        }
    }
}
