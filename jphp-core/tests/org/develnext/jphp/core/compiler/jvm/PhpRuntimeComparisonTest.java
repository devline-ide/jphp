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

/** Actual comparison SEND roots retain both values across warning callbacks and scalar result publication. */
public class PhpRuntimeComparisonTest extends JvmCompilerCase {
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
                    for(var clazz:module.getClasses())if(clazz.methodDestruct!=null)authority.registerMethod(clazz.methodDestruct,clazz.getData(),(p,m,b)->{});
                    authority.registerModule(module,module.getData(),(p,m,b)->{});
                    try(var result=authority.include(module)){assertTrue(result.raw().isNull());}
                    env.getDefaultBuffer().flush();assertEquals(expected,output.toString(StandardCharsets.UTF_8));
                }catch(Throwable failure){throw new AssertionError("debug="+debug+" dump="+dump+" source="+source,failure);}
                finally{env.doFinal();authority.close();domain.collectCycles();}
                assertEquals(0,domain.graph().liveNodes());
            }
        }
    }
    @Test public void allSevenOperatorsPublishTheirActualScalarResult()throws Throwable {
        execute("$a=2;$b=3;echo serialize([$a==$b,$a!=$b,$a<$b,$a<=$b,$a>$b,$a>=$b,$a<=>$b]);","a:7:{i:0;b:0;i:1;b:1;i:2;b:1;i:3;b:1;i:4;b:0;i:5;b:0;i:6;i:-1;}");plainInclude("$a=2;$b=3;echo serialize([$a==$b,$a!=$b,$a<$b,$a<=$b,$a>$b,$a>=$b,$a<=>$b]);","a:7:{i:0;b:0;i:1;b:1;i:2;b:1;i:3;b:1;i:4;b:0;i:5;b:0;i:6;i:-1;}");
        execute("declare(strict_types=1);$a=2;$b=3;echo serialize([$a==$b,$a!=$b,$a<$b,$a<=$b,$a>$b,$a>=$b,$a<=>$b]);","a:7:{i:0;b:0;i:1;b:1;i:2;b:1;i:3;b:1;i:4;b:0;i:5;b:0;i:6;i:-1;}");plainInclude("declare(strict_types=1);$a=2;$b=3;echo serialize([$a==$b,$a!=$b,$a<$b,$a<=$b,$a>$b,$a>=$b,$a<=>$b]);","a:7:{i:0;b:0;i:1;b:1;i:2;b:1;i:3;b:1;i:4;b:0;i:5;b:0;i:6;i:-1;}");
    }
    @Test public void numericAndNonnumericStringsKeepPhpComparisonRules()throws Throwable {
        execute("$a=0;$b='x';$c='02';$d=2;echo serialize([$a==$b,$a<$b,$b>$a,$c==$d,$c<=>$d]);","a:5:{i:0;b:0;i:1;b:1;i:2;b:1;i:3;b:1;i:4;i:0;}");plainInclude("$a=0;$b='x';$c='02';$d=2;echo serialize([$a==$b,$a<$b,$b>$a,$c==$d,$c<=>$d]);","a:5:{i:0;b:0;i:1;b:1;i:2;b:1;i:3;b:1;i:4;i:0;}");
        execute("declare(strict_types=1);$a=0;$b='x';$c='02';$d=2;echo serialize([$a==$b,$a<$b,$b>$a,$c==$d,$c<=>$d]);","a:5:{i:0;b:0;i:1;b:1;i:2;b:1;i:3;b:1;i:4;i:0;}");plainInclude("declare(strict_types=1);$a=0;$b='x';$c='02';$d=2;echo serialize([$a==$b,$a<$b,$b>$a,$c==$d,$c<=>$d]);","a:5:{i:0;b:0;i:1;b:1;i:2;b:1;i:3;b:1;i:4;i:0;}");
    }
    @Test public void arrayLiteralProducerTransfersToTheActualOperandSend()throws Throwable {
        execute("$a=[];echo serialize([$a==[],$a<=>[]]);","a:2:{i:0;b:1;i:1;i:0;}");plainInclude("$a=[];echo serialize([$a==[],$a<=>[]]);","a:2:{i:0;b:1;i:1;i:0;}");
        execute("declare(strict_types=1);$a=[];echo serialize([$a==[],$a<=>[]]);","a:2:{i:0;b:1;i:1;i:0;}");plainInclude("declare(strict_types=1);$a=[];echo serialize([$a==[],$a<=>[]]);","a:2:{i:0;b:1;i:1;i:0;}");
    }
    @Test public void counterLoopWithComparisonIncrementAndCastRunsEveryIteration()throws Throwable {
        execute("$i=0;while($i<3){echo 'iteration;';$GLOBALS['copy']=(object)['p'=>[]];$i++;}echo serialize([$i,$GLOBALS['copy']]);","iteration;iteration;iteration;a:2:{i:0;i:3;i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");plainInclude("$i=0;while($i<3){echo 'iteration;';$GLOBALS['copy']=(object)['p'=>[]];$i++;}echo serialize([$i,$GLOBALS['copy']]);","iteration;iteration;iteration;a:2:{i:0;i:3;i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");
        execute("declare(strict_types=1);$i=0;while($i<3){echo 'iteration;';$GLOBALS['copy']=(object)['p'=>[]];$i++;}echo serialize([$i,$GLOBALS['copy']]);","iteration;iteration;iteration;a:2:{i:0;i:3;i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");plainInclude("declare(strict_types=1);$i=0;while($i<3){echo 'iteration;';$GLOBALS['copy']=(object)['p'=>[]];$i++;}echo serialize([$i,$GLOBALS['copy']]);","iteration;iteration;iteration;a:2:{i:0;i:3;i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");
    }
    @Test public void directCvReadsAndPostIncrementKeepNativeEvaluationOrder()throws Throwable {
        execute("$a=2;$b=$a==$a++;$c=$a<$a++;echo serialize([$a,$b,$c]);","a:3:{i:0;i:4;i:1;b:0;i:2;b:0;}");plainInclude("$a=2;$b=$a==$a++;$c=$a<$a++;echo serialize([$a,$b,$c]);","a:3:{i:0;i:4;i:1;b:0;i:2;b:0;}");
        execute("declare(strict_types=1);$a=2;$b=$a==$a++;$c=$a<$a++;echo serialize([$a,$b,$c]);","a:3:{i:0;i:4;i:1;b:0;i:2;b:0;}");plainInclude("declare(strict_types=1);$a=2;$b=$a==$a++;$c=$a<$a++;echo serialize([$a,$b,$c]);","a:3:{i:0;i:4;i:1;b:0;i:2;b:0;}");
    }
    @Test public void objectWarningKeepsOperandAliveAfterHandlerReplacesOriginal()throws Throwable {
        execute("error_reporting(0);class C {function __destruct(){echo 'd;';}}$a=new C;set_error_handler(function($n,$s)use(&$a){echo 'w;';$a=null;return true;});$r=$a==1;restore_error_handler();echo 'after;';echo serialize([$r,$a]);","w;d;after;a:2:{i:0;b:1;i:1;N;}");
        execute("declare(strict_types=1);error_reporting(0);class C {function __destruct(){echo 'd;';}}$a=new C;set_error_handler(function($n,$s)use(&$a){echo 'w;';$a=null;return true;});$r=$a==1;restore_error_handler();echo 'after;';echo serialize([$r,$a]);","w;d;after;a:2:{i:0;b:1;i:1;N;}");
    }
    @Test public void nestedArrayOperandRetainsObjectUntilTheComparisonReturns()throws Throwable {
        execute("error_reporting(0);class C {function __destruct(){echo 'd;';}}$a=[new C];$b=[1];set_error_handler(function($n,$s)use(&$a){echo 'w;';$a=[];return true;});$r=$a==$b;restore_error_handler();echo 'after;';echo serialize([$r,$a]);","w;d;after;a:2:{i:0;b:1;i:1;a:0:{}}");
        execute("declare(strict_types=1);error_reporting(0);class C {function __destruct(){echo 'd;';}}$a=[new C];$b=[1];set_error_handler(function($n,$s)use(&$a){echo 'w;';$a=[];return true;});$r=$a==$b;restore_error_handler();echo 'after;';echo serialize([$r,$a]);","w;d;after;a:2:{i:0;b:1;i:1;a:0:{}}");
    }
    @Test public void stdClassPropertiesAndReferencePayloadsCompareAsValues()throws Throwable {
        execute("$v=3;$a=(object)['p'=>&$v];$b=(object)['p'=>3];echo serialize([$a==$b,$a<=>$b]);","a:2:{i:0;b:1;i:1;i:0;}");plainInclude("$v=3;$a=(object)['p'=>&$v];$b=(object)['p'=>3];echo serialize([$a==$b,$a<=>$b]);","a:2:{i:0;b:1;i:1;i:0;}");
        execute("declare(strict_types=1);$v=3;$a=(object)['p'=>&$v];$b=(object)['p'=>3];echo serialize([$a==$b,$a<=>$b]);","a:2:{i:0;b:1;i:1;i:0;}");plainInclude("declare(strict_types=1);$v=3;$a=(object)['p'=>&$v];$b=(object)['p'=>3];echo serialize([$a==$b,$a<=>$b]);","a:2:{i:0;b:1;i:1;i:0;}");
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
