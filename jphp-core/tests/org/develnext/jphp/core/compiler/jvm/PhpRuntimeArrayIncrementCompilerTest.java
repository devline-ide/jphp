package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.LangFunctions;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Addressed increment retains the fetched backing during actual warning callbacks. */
public class PhpRuntimeArrayIncrementCompilerTest extends JvmCompilerCase {
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void warningFetchUsesRetainedCopiesAndActualReplacementOwners()throws Throwable {
        String[] actions={"", "$local=$a;", "$copy=$a;", "$copy=[$a];", "$GLOBALS['alias']=&$a;", "$copy=$a;$a=[9];", "$copy=$a;unset($a);", "$a=[9];"};
        for(int route=0;route<actions.length;route++)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            String source="$a=[];$ev=[];$copy=null;$alias=null;set_error_handler(function($n,$s)use(&$a,&$ev,&$copy,&$alias){$ev[]=[$n,$s];"+actions[route]+"return true;});$r=$a[2]++;restore_error_handler();return 7;";
            var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try {
                    for(var member:LangFunctions.class.getDeclaredMethods())if(Set.of("set_error_handler","restore_error_handler").contains(member.getName()))
                        authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                            public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                            public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){}
                        });
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,b)->{});
                    authority.registerModule(module,module.getData(),(p,m,b)->{});
                    try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}
                    var a=authority.globalAddress("a").toValue(ArrayMemory.class);var copy=authority.globalAddress("copy").toValue();
                    assertTrue("postincrement captured NULL route="+route,authority.globalAddress("r").toValue().isNull());
                    if(route==0||route==1||route==4) {assertEquals(1,a.size());assertEquals(1,a.getByScalar(LongMemory.valueOf(2)).toLong());}
                    else if(route==5||route==7) {assertEquals(1,a.size());assertEquals(9,a.getByScalar(LongMemory.valueOf(0)).toLong());}
                    else assertEquals("retained backing canceled insertion route="+route,0,a.size());
                    if(route==2||route==6)assertEquals(0,((ArrayMemory)copy).size());
                    if(route==3)assertEquals(0,((ArrayMemory)copy).getByScalar(LongMemory.valueOf(0)).toValue(ArrayMemory.class).size());
                    if(route==5){assertEquals(1,((ArrayMemory)copy).size());assertEquals(1,((ArrayMemory)copy).getByScalar(LongMemory.valueOf(2)).toLong());}
                    if(route==4)assertSame(a.getRuntimeOrigin(),authority.globalAddress("alias").toValue(ArrayMemory.class).getRuntimeOrigin());
                    var ev=authority.globalAddress("ev").toValue(ArrayMemory.class);assertEquals(1,ev.size());
                    var warning=ev.getByScalar(LongMemory.valueOf(0)).toValue(ArrayMemory.class);
                    assertEquals(2,warning.getByScalar(LongMemory.valueOf(0)).toLong());
                    assertEquals("Undefined array key 2",warning.getByScalar(LongMemory.valueOf(1)).toString());
                }catch(Throwable failure){throw new AssertionError("route="+route+" debug="+debug+" dump="+dump,failure);}
                finally {env.doFinal();authority.close();}
                assertEquals(0,domain.graph().liveNodes());
            }
        }
    }
    @Test public void multilineFetchKeepsItsDiagnosticTrace()throws Throwable {
        String source="$a=[];$ev=[];\nset_error_handler(function($n,$s,$f,$line)use(&$ev){$ev[]=[$line];return true;});\n$r=\n$a[\n2\n]++\n;restore_error_handler();return 7;";
        for(String initial:new String[]{"[]","[2=>false]","[2=>'abc!']"})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            var module=compile(env,source.replace("$a=[]","$a="+initial),dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try {
                    for(var member:LangFunctions.class.getDeclaredMethods())if(Set.of("set_error_handler","restore_error_handler").contains(member.getName()))
                        authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                            public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                            public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){}
                        });
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,b)->{});
                    authority.registerModule(module,module.getData(),(p,m,b)->{});
                    try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}
                    var ev=authority.globalAddress("ev").toValue(ArrayMemory.class);
                    assertEquals(5,ev.getByScalar(LongMemory.valueOf(0)).toValue(ArrayMemory.class).getByScalar(LongMemory.valueOf(0)).toLong());
                }finally{env.doFinal();authority.close();}
                assertEquals(0,domain.graph().liveNodes());
            }
        }
    }

    @Test public void capturedScalarWritesItsOriginalBucketWithoutCreatingAnArrayOwner()throws Throwable {
        String[] actions={"$a[2]=[9];", "unset($a);", "$a=[9];", "$copy=$a;$a[2]=[9];", "$x=[9];"};
        for(int route=0;route<actions.length;route++)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            String source="$x=false;$a="+(route==4?"[&$x]":"[2=>false]")+";$copy=null;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev,&$copy,&$x){$ev[]=[$n,$s];"+actions[route]+"return true;});$r=$a["+(route==4?0:2)+"]++;restore_error_handler();return 7;";
            var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try {
                    for(var member:LangFunctions.class.getDeclaredMethods())if(Set.of("set_error_handler","restore_error_handler").contains(member.getName()))
                        authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                            public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                            public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){}
                        });
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,b)->{});
                    authority.registerModule(module,module.getData(),(p,m,b)->{});
                    try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}
                    assertSame(Memory.FALSE,authority.globalAddress("r").toValue());
                    Memory a=authority.globalAddress("a").toValue();
                    if(route==2)assertEquals(9,((ArrayMemory)a).getByScalar(LongMemory.valueOf(0)).toLong());
                    else if(route==3) {
                        assertEquals(9,((ArrayMemory)a).getByScalar(LongMemory.valueOf(2)).toValue(ArrayMemory.class).getByScalar(LongMemory.valueOf(0)).toLong());
                        assertSame(Memory.FALSE,authority.globalAddress("copy").toValue(ArrayMemory.class).getByScalar(LongMemory.valueOf(2)).toValue());
                    }else assertSame(Memory.FALSE,((ArrayMemory)a).getByScalar(LongMemory.valueOf(route==4?0:2)).toValue());
                    if(route==4)assertSame(Memory.FALSE,authority.globalAddress("x").toValue());
                    assertEquals(1,authority.globalAddress("ev").toValue(ArrayMemory.class).size());
                }catch(Throwable failure){throw new AssertionError("scalar route="+route+" debug="+debug+" dump="+dump,failure);}
                finally{env.doFinal();authority.close();}
                assertEquals(0,domain.graph().liveNodes());
            }
        }
    }

    @Test public void unknownKeyAndNestedTargetsRejectBeforeEffects()throws Throwable {
        for(String source:new String[]{"echo 'entered';$a=[];$k=2;$r=$a[$k]++;return 7;","echo 'entered';$a=[[]];$r=$a[0][2]++;return 7;"})
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var out=new ByteArrayOutputStream();var env=new Environment(scope,out);var domain=new PhpOwnershipDomain();
            var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try {try{authority.registerModule(module,module.getData(),(p,m,b)->{});fail("Unproven array increment admitted");}catch(IOException|IllegalArgumentException expected){}assertEquals(0,out.size());}
                finally{env.doFinal();authority.close();}
                assertEquals(0,domain.graph().liveNodes());
            }
        }
    }
}
