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

/** The actual cast consumes its ARRAY producer and keeps property VALUE/CELL publishers alive. */
public class PhpRuntimeObjectCastTest extends JvmCompilerCase {
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
    @Test public void repeatedCastSiteTransfersEachIterationToItsActualGlobalOrLocalOwner()throws Throwable {
        for(String destination:new String[]{"$GLOBALS['copy']","$copy"}) {
            String source="$again=true;$next=true;$last=true;while($again){echo 'iteration;';"+destination+"=(object)['p'=>[]];$again=$next;$next=$last;$last=false;}echo serialize("+destination+");";
            String expected="iteration;iteration;iteration;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}";
            execute(source,expected);execute("declare(strict_types=1);"+source,expected);
            plainInclude(source,expected);plainInclude("declare(strict_types=1);"+source,expected);
        }
    }
    @Test public void unchangedOriginalObjectCopyRetainsItsPhysicalPropertyArrayValue()throws Throwable {
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_array_warning_storage_owners.tsv")) {
            assertNotNull(input);String[] selected=null;
            for(String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R"))if(line.startsWith("object_copy_retained\t"))selected=line.split("\\t",-1);
            assertNotNull(selected);var decoder=Base64.getDecoder();String source=new String(decoder.decode(selected[1]),StandardCharsets.UTF_8),expected=new String(decoder.decode(selected[2]),StandardCharsets.UTF_8);
            execute(source.substring(5),expected);execute("declare(strict_types=1);"+source.substring(5),expected);
        }
    }
    @Test public void ordinaryCastTransfersEmptyAndNumericKeyArrays()throws Throwable {
        execute("$GLOBALS['copy']=(object)[];echo serialize($GLOBALS['copy']);","O:8:\"stdClass\":0:{}");
        execute("$GLOBALS['copy']=(object)[3=>8,'value'=>null];echo serialize($GLOBALS['copy']);","O:8:\"stdClass\":2:{s:1:\"3\";i:8;s:5:\"value\";N;}");
    }
    @Test public void ordinaryCastPreservesReferencedPropertyAliases()throws Throwable {
        execute("$v=9;$GLOBALS['copy']=(object)['p'=>&$v];$v=11;echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:11;}i:1;i:11;}");
    }
    @Test public void nested_literalMatchesFreshNativeWeakAndStrictControls()throws Throwable {
        execute("$GLOBALS['copy']=(object)['p'=>[]];echo serialize($GLOBALS['copy']);","O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}");
        execute("declare(strict_types=1);$GLOBALS['copy']=(object)['p'=>[]];echo serialize($GLOBALS['copy']);","O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}");
    }

    @Test public void value_copy_cowMatchesFreshNativeWeakAndStrictControls()throws Throwable {
        execute("$a=[];$GLOBALS['copy']=(object)['p'=>$a];$a[0]=9;echo serialize([$a,$GLOBALS['copy']]);","a:2:{i:0;a:1:{i:0;i:9;}i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");
        execute("declare(strict_types=1);$a=[];$GLOBALS['copy']=(object)['p'=>$a];$a[0]=9;echo serialize([$a,$GLOBALS['copy']]);","a:2:{i:0;a:1:{i:0;i:9;}i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");
    }

    @Test public void repeat_value_keyMatchesFreshNativeWeakAndStrictControls()throws Throwable {
        execute("$a=[];$GLOBALS['copy']=(object)['p'=>$a,'p'=>[]];$a[0]=9;echo serialize([$a,$GLOBALS['copy']]);","a:2:{i:0;a:1:{i:0;i:9;}i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");
        execute("declare(strict_types=1);$a=[];$GLOBALS['copy']=(object)['p'=>$a,'p'=>[]];$a[0]=9;echo serialize([$a,$GLOBALS['copy']]);","a:2:{i:0;a:1:{i:0;i:9;}i:1;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}}");
    }

    @Test public void release_table_ownerMatchesFreshNativeWeakAndStrictControls()throws Throwable {
        execute("error_reporting(0);$a=[];$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$GLOBALS['copy']=(object)['p'=>$a];unset($GLOBALS['copy']);return true;});$r=$a[2]++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;N;i:1;a:1:{i:2;i:1;}i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:21:\"Undefined array key 2\";}}}");
        execute("declare(strict_types=1);error_reporting(0);$a=[];$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$GLOBALS['copy']=(object)['p'=>$a];unset($GLOBALS['copy']);return true;});$r=$a[2]++;restore_error_handler();echo serialize([$r,$a,$ev]);","a:3:{i:0;N;i:1;a:1:{i:2;i:1;}i:2;a:1:{i:0;a:2:{i:0;i:2;i:1;s:21:\"Undefined array key 2\";}}}");
    }

    @Test public void repeatedKeysReplacePhysicalCellFacetsWithoutWritingThroughOldAliases()throws Throwable {
        execute("$v=9;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>10];echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:10;}i:1;i:9;}");
        execute("declare(strict_types=1);$v=9;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>10];echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:10;}i:1;i:9;}");
        execute("$v=9;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>[]];echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}i:1;i:9;}");
        execute("declare(strict_types=1);$v=9;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>[]];echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}i:1;i:9;}");
        execute("$v=9;$GLOBALS['copy']=(object)['p'=>1,'p'=>&$v];$v=11;echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:11;}i:1;i:11;}");
        execute("declare(strict_types=1);$v=9;$GLOBALS['copy']=(object)['p'=>1,'p'=>&$v];$v=11;echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:11;}i:1;i:11;}");
        execute("$v=9;$w=10;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>&$w];$v=11;$w=12;echo serialize([$GLOBALS['copy'],$v,$w]);","a:3:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:12;}i:1;i:11;i:2;i:12;}");
        execute("declare(strict_types=1);$v=9;$w=10;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>&$w];$v=11;$w=12;echo serialize([$GLOBALS['copy'],$v,$w]);","a:3:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:12;}i:1;i:11;i:2;i:12;}");
    }

    @Test public void unresolvedKeyConversionsRejectBeforeEarlierBodyEffects()throws Throwable {
        for(String initialization:new String[]{"$k='p';","$k=(object)[];"})
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
            var module=compile(env,initialization+"echo 'effect';$GLOBALS['copy']=(object)[$k=>[]];",dump);
            var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try {
                    var member=module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                    assertTrue(plan.unsupported().toString(),plan.unsupported().contains("literal-entry-key-conversion"));
                    var failure=assertThrows(Throwable.class,()->authority.registerModule(module,module.getData(),(p,m,b)->{}));
                    assertTrue(failure.toString(),failure instanceof IOException||failure instanceof IllegalArgumentException);
                    assertEquals(0,output.size());assertEquals(0,domain.graph().liveNodes());
                }finally{env.doFinal();authority.close();}
            }
        }
    }

    @Test public void ordinaryIncludeRepeatedKeysMatchNativeWithoutOwnershipActivation()throws Throwable {
        plainInclude("$v=9;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>10];echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:10;}i:1;i:9;}");
        plainInclude("declare(strict_types=1);$v=9;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>10];echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:10;}i:1;i:9;}");
        plainInclude("$v=9;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>[]];echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}i:1;i:9;}");
        plainInclude("declare(strict_types=1);$v=9;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>[]];echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";a:0:{}}i:1;i:9;}");
        plainInclude("$v=9;$GLOBALS['copy']=(object)['p'=>1,'p'=>&$v];$v=11;echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:11;}i:1;i:11;}");
        plainInclude("declare(strict_types=1);$v=9;$GLOBALS['copy']=(object)['p'=>1,'p'=>&$v];$v=11;echo serialize([$GLOBALS['copy'],$v]);","a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:11;}i:1;i:11;}");
        plainInclude("$v=9;$w=10;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>&$w];$v=11;$w=12;echo serialize([$GLOBALS['copy'],$v,$w]);","a:3:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:12;}i:1;i:11;i:2;i:12;}");
        plainInclude("declare(strict_types=1);$v=9;$w=10;$GLOBALS['copy']=(object)['p'=>&$v,'p'=>&$w];$v=11;$w=12;echo serialize([$GLOBALS['copy'],$v,$w]);","a:3:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:12;}i:1;i:11;i:2;i:12;}");
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
