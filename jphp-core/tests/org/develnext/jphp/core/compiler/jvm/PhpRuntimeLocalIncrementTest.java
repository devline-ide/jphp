package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual CV unary opcodes publish their scalar result and retain warning continuation custody. */
public class PhpRuntimeLocalIncrementTest extends JvmCompilerCase {
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);return module;
    }
    private void execute(String source,String expected)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
            env.getDefaultBuffer().setChunkSize(0);var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try {
                    var method=module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),method.getName(),PhpOwnershipExecution.descriptor(method));
                    assertEquals(Collections.emptyList(),plan.unsupported());
                    Set<java.lang.reflect.Method> registered=new HashSet<>();
                    var dependencies=new ArrayList<>(plan.nativeMembers());
                    for(var closure:module.getClosures()){var target=closure.findMethod("__invoke");var child=PhpRuntimeOwnership.readRuntimePlan(closure.getData(),target.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(target.getNativeMethod()));assertEquals(Collections.emptyList(),child.unsupported());dependencies.addAll(child.nativeMembers());}
                    for(var dependency:dependencies) {
                        var owner=Class.forName(dependency.owner().replace('/','.'));
                        var selected=Arrays.stream(owner.getDeclaredMethods()).filter(m->m.getName().equals(dependency.name())&&PhpOwnershipExecution.descriptor(m).equals(dependency.descriptor())).findFirst().orElseThrow();
                        if(!registered.add(selected))continue;
                        Set<Integer> counts=new HashSet<>();for(var candidate:dependencies)if(candidate.owner().equals(dependency.owner())&&candidate.name().equals(dependency.name())&&candidate.descriptor().equals(dependency.descriptor()))counts.add(candidate.argc());
                        authority.registerNative(selected,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                            public void validateMember(java.lang.reflect.Method actual){assertEquals(selected,actual);}
                            public void validateArguments(List<PhpRuntimeOwnership.Value> actual){assertTrue(counts.contains(actual.size()));}
                        });
                    }
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,b)->{});
                    authority.registerModule(module,module.getData(),(p,m,b)->{});
                    try(var result=authority.include(module)){assertTrue(result.raw().isNull());}
                    env.getDefaultBuffer().flush();assertEquals(expected,output.toString(StandardCharsets.UTF_8));
                }catch(Throwable failure){throw new AssertionError("debug="+debug+" dump="+dump+" source="+source,failure);}
                finally{env.doFinal();authority.close();domain.collectCycles();}
                assertEquals(0,domain.graph().liveNodes());
            }
        }
    }
    @Test public void prePostIncrementAndDecrement()throws Throwable {
        execute("$a=2;$b=$a++;$c=++$a;$d=$a--;$e=--$a;echo serialize([$a,$b,$c,$d,$e]);","a:5:{i:0;i:2;i:1;i:2;i:2;i:4;i:3;i:4;i:4;i:2;}");plainInclude("$a=2;$b=$a++;$c=++$a;$d=$a--;$e=--$a;echo serialize([$a,$b,$c,$d,$e]);","a:5:{i:0;i:2;i:1;i:2;i:2;i:4;i:3;i:4;i:4;i:2;}");
        execute("declare(strict_types=1);$a=2;$b=$a++;$c=++$a;$d=$a--;$e=--$a;echo serialize([$a,$b,$c,$d,$e]);","a:5:{i:0;i:2;i:1;i:2;i:2;i:4;i:3;i:4;i:4;i:2;}");plainInclude("declare(strict_types=1);$a=2;$b=$a++;$c=++$a;$d=$a--;$e=--$a;echo serialize([$a,$b,$c,$d,$e]);","a:5:{i:0;i:2;i:1;i:2;i:2;i:4;i:3;i:4;i:4;i:2;}");
    }
    @Test public void canonicalAliasesKeepOnePayload()throws Throwable {
        execute("$a=2;$b=&$a;$x=$b++;$y=--$a;echo serialize([$a,$b,$x,$y]);","a:4:{i:0;i:2;i:1;i:2;i:2;i:2;i:3;i:2;}");plainInclude("$a=2;$b=&$a;$x=$b++;$y=--$a;echo serialize([$a,$b,$x,$y]);","a:4:{i:0;i:2;i:1;i:2;i:2;i:2;i:3;i:2;}");
        execute("declare(strict_types=1);$a=2;$b=&$a;$x=$b++;$y=--$a;echo serialize([$a,$b,$x,$y]);","a:4:{i:0;i:2;i:1;i:2;i:2;i:2;i:3;i:2;}");plainInclude("declare(strict_types=1);$a=2;$b=&$a;$x=$b++;$y=--$a;echo serialize([$a,$b,$x,$y]);","a:4:{i:0;i:2;i:1;i:2;i:2;i:2;i:3;i:2;}");
    }
    @Test public void numericStringAndIntegerOverflow()throws Throwable {
        execute("$a='9';$b=$a++;$c=9223372036854775807;$d=$c++;echo serialize([$a,$b,$c,$d]);","a:4:{i:0;i:10;i:1;s:1:\"9\";i:2;d:9.223372036854776E+18;i:3;i:9223372036854775807;}");plainInclude("$a='9';$b=$a++;$c=9223372036854775807;$d=$c++;echo serialize([$a,$b,$c,$d]);","a:4:{i:0;i:10;i:1;s:1:\"9\";i:2;d:9.223372036854776E+18;i:3;i:9223372036854775807;}");
        execute("declare(strict_types=1);$a='9';$b=$a++;$c=9223372036854775807;$d=$c++;echo serialize([$a,$b,$c,$d]);","a:4:{i:0;i:10;i:1;s:1:\"9\";i:2;d:9.223372036854776E+18;i:3;i:9223372036854775807;}");plainInclude("declare(strict_types=1);$a='9';$b=$a++;$c=9223372036854775807;$d=$c++;echo serialize([$a,$b,$c,$d]);","a:4:{i:0;i:10;i:1;s:1:\"9\";i:2;d:9.223372036854776E+18;i:3;i:9223372036854775807;}");
    }
    @Test public void boolWarningRestoresCapturedScalarAfterHandlerWrites()throws Throwable {
        execute("error_reporting(0);$a=false;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=9;return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;b:0;i:1;b:0;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Increment on type bool has no effect, this will change in the next major version of PHP\";}}}");plainInclude("error_reporting(0);$a=false;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=9;return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;b:0;i:1;b:0;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Increment on type bool has no effect, this will change in the next major version of PHP\";}}}");
        execute("declare(strict_types=1);error_reporting(0);$a=false;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=9;return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;b:0;i:1;b:0;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Increment on type bool has no effect, this will change in the next major version of PHP\";}}}");plainInclude("declare(strict_types=1);error_reporting(0);$a=false;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=9;return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;b:0;i:1;b:0;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Increment on type bool has no effect, this will change in the next major version of PHP\";}}}");
    }
    @Test public void boolWarningReleasesHandlerArrayPayload()throws Throwable {
        execute("error_reporting(0);$a=true;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=[];return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;b:1;i:1;b:1;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Increment on type bool has no effect, this will change in the next major version of PHP\";}}}");plainInclude("error_reporting(0);$a=true;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=[];return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;b:1;i:1;b:1;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Increment on type bool has no effect, this will change in the next major version of PHP\";}}}");
        execute("declare(strict_types=1);error_reporting(0);$a=true;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=[];return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;b:1;i:1;b:1;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Increment on type bool has no effect, this will change in the next major version of PHP\";}}}");plainInclude("declare(strict_types=1);error_reporting(0);$a=true;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=[];return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;b:1;i:1;b:1;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Increment on type bool has no effect, this will change in the next major version of PHP\";}}}");
    }
    @Test public void stringWarningPreservesValueCopyAndCommittedTarget()throws Throwable {
        execute("error_reporting(0);$a='az';$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$GLOBALS['copy']=$a;$a='zz';return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$GLOBALS['copy'],$ev]);","a:4:{i:0;s:2:\"az\";i:1;s:2:\"ba\";i:2;s:2:\"az\";i:3;a:1:{i:0;a:2:{i:0;i:8192;i:1;s:74:\"Increment on non-numeric string is deprecated, use str_increment() instead\";}}}");plainInclude("error_reporting(0);$a='az';$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$GLOBALS['copy']=$a;$a='zz';return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$GLOBALS['copy'],$ev]);","a:4:{i:0;s:2:\"az\";i:1;s:2:\"ba\";i:2;s:2:\"az\";i:3;a:1:{i:0;a:2:{i:0;i:8192;i:1;s:74:\"Increment on non-numeric string is deprecated, use str_increment() instead\";}}}");
        execute("declare(strict_types=1);error_reporting(0);$a='az';$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$GLOBALS['copy']=$a;$a='zz';return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$GLOBALS['copy'],$ev]);","a:4:{i:0;s:2:\"az\";i:1;s:2:\"ba\";i:2;s:2:\"az\";i:3;a:1:{i:0;a:2:{i:0;i:8192;i:1;s:74:\"Increment on non-numeric string is deprecated, use str_increment() instead\";}}}");plainInclude("declare(strict_types=1);error_reporting(0);$a='az';$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$GLOBALS['copy']=$a;$a='zz';return true;});$r=$a++;restore_error_handler();echo serialize([$r,$a,$GLOBALS['copy'],$ev]);","a:4:{i:0;s:2:\"az\";i:1;s:2:\"ba\";i:2;s:2:\"az\";i:3;a:1:{i:0;a:2:{i:0;i:8192;i:1;s:74:\"Increment on non-numeric string is deprecated, use str_increment() instead\";}}}");
    }
    @Test public void nullDecrementRetainsNoEffectValueAcrossHandler()throws Throwable {
        execute("error_reporting(0);$a=null;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=[];return true;});$r=--$a;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;N;i:1;N;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Decrement on type null has no effect, this will change in the next major version of PHP\";}}}");plainInclude("error_reporting(0);$a=null;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=[];return true;});$r=--$a;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;N;i:1;N;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Decrement on type null has no effect, this will change in the next major version of PHP\";}}}");
        execute("declare(strict_types=1);error_reporting(0);$a=null;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=[];return true;});$r=--$a;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;N;i:1;N;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Decrement on type null has no effect, this will change in the next major version of PHP\";}}}");plainInclude("declare(strict_types=1);error_reporting(0);$a=null;$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$a=[];return true;});$r=--$a;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;N;i:1;N;i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:87:\"Decrement on type null has no effect, this will change in the next major version of PHP\";}}}");
    }
    @Test public void sameLocalOpcodeRunsOnEveryLoopIteration()throws Throwable {
        execute("$again=true;$next=true;$last=true;$i=0;while($again){echo 'iteration;';$GLOBALS['copy']=(object)['p'=>[]];$i++;$again=$next;$next=$last;$last=false;}echo serialize([$i,$GLOBALS['copy']]);","iteration;iteration;iteration;a:2:{i:0;i:3;i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");plainInclude("$again=true;$next=true;$last=true;$i=0;while($again){echo 'iteration;';$GLOBALS['copy']=(object)['p'=>[]];$i++;$again=$next;$next=$last;$last=false;}echo serialize([$i,$GLOBALS['copy']]);","iteration;iteration;iteration;a:2:{i:0;i:3;i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");
        execute("declare(strict_types=1);$again=true;$next=true;$last=true;$i=0;while($again){echo 'iteration;';$GLOBALS['copy']=(object)['p'=>[]];$i++;$again=$next;$next=$last;$last=false;}echo serialize([$i,$GLOBALS['copy']]);","iteration;iteration;iteration;a:2:{i:0;i:3;i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");plainInclude("declare(strict_types=1);$again=true;$next=true;$last=true;$i=0;while($again){echo 'iteration;';$GLOBALS['copy']=(object)['p'=>[]];$i++;$again=$next;$next=$last;$last=false;}echo serialize([$i,$GLOBALS['copy']]);","iteration;iteration;iteration;a:2:{i:0;i:3;i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");
    }
    private void plainInclude(String source,String expected)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            try {
                var module=compile(env,source,dump);assertNull(env.getRuntimeOwnership());module.include(env);env.getDefaultBuffer().flush();
                assertEquals("debug="+debug+" dump="+dump+" source="+source,expected,output.toString(StandardCharsets.UTF_8));
            }finally{env.doFinal();}
        }
    }

}
