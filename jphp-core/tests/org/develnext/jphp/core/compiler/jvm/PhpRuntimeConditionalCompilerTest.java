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

/** Actual conditional bytecode publishes only the selected child into its JOIN root. */
public class PhpRuntimeConditionalCompilerTest extends JvmCompilerCase {
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
                    for(var dependency:plan.nativeMembers()) {
                        var owner=Class.forName(dependency.owner().replace('/','.'));
                        var selected=Arrays.stream(owner.getDeclaredMethods()).filter(m->m.getName().equals(dependency.name())&&PhpOwnershipExecution.descriptor(m).equals(dependency.descriptor())).findFirst().orElseThrow();
                        if(!registered.add(selected))continue;
                        Set<Integer> counts=new HashSet<>();for(var candidate:plan.nativeMembers())if(candidate.owner().equals(dependency.owner())&&candidate.name().equals(dependency.name())&&candidate.descriptor().equals(dependency.descriptor()))counts.add(candidate.argc());
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
    @Test public void originalCoalesceProjectionsReadQuietlyAndKeepFalseAndArrayValues()throws Throwable {
        String[] initial={"", "$copy=null;", "$copy=false;", "$copy=[];", "$copy=[[9]];"};
        String[] expected={"a:1:{i:0;s:6:\"absent\";}","a:1:{i:0;s:6:\"absent\";}","a:1:{i:0;b:0;}","a:1:{i:0;a:0:{}}","a:1:{i:0;a:1:{i:0;a:1:{i:0;i:9;}}}"};
        for(int i=0;i<initial.length;i++)execute(initial[i]+"echo serialize([$GLOBALS[\"copy\"]??\"absent\"]);",expected[i]);
        for(String prefix:new String[]{"", "$peek=null;", "$peek=[9];"})
            execute(prefix+"echo serialize([$GLOBALS[\"peek\"]??null]);",prefix.equals("$peek=[9];")?"a:1:{i:0;a:1:{i:0;i:9;}}":"a:1:{i:0;N;}");
    }
    @Test public void shortTernaryRetainsTrueHeapAndTransfersOnlyItsSelectedAlternative()throws Throwable {
        for(String initial:new String[]{"null","false","[]","[9]"})
            execute("$copy="+initial+";echo serialize([$GLOBALS['copy']?:[]]);",initial.equals("[9]")?"a:1:{i:0;a:1:{i:0;i:9;}}":"a:1:{i:0;a:0:{}}");
    }
    @Test public void fullTernaryChoosesOneHeapProducerAndDoesNotReadItsOtherArm()throws Throwable {
        for(boolean flag:new boolean[]{false,true}) {
            execute("$flag="+flag+";$left=[1];$right=[2];echo serialize([$GLOBALS['flag']?$GLOBALS['left']:$GLOBALS['right']]);","a:1:{i:0;a:1:{i:0;i:"+(flag?1:2)+";}}");
            execute("$flag="+flag+";$left=[1];$right=[2];echo serialize([$GLOBALS['flag']?[]:$GLOBALS['right']]);",flag?"a:1:{i:0;a:0:{}}":"a:1:{i:0;a:1:{i:0;i:2;}}");
            execute("$flag="+flag+";$good=[];echo serialize([$GLOBALS['flag']?"+(flag?"$GLOBALS['good']:$GLOBALS['missing']":"$GLOBALS['missing']:$GLOBALS['good']")+"]);","a:1:{i:0;a:0:{}}");
        }
    }
    @Test public void coalesceEvaluatesItsActualAlternativeWithOneOrderedWarning()throws Throwable {
        String[] prefix={"", "$copy=null;", "$copy=false;", "$copy=[];"};
        String[] expected={"a:2:{i:0;b:1;i:1;a:1:{i:0;a:2:{i:0;i:1024;i:1;s:9:\"alternate\";}}}","a:2:{i:0;b:1;i:1;a:1:{i:0;a:2:{i:0;i:1024;i:1;s:9:\"alternate\";}}}","a:2:{i:0;b:0;i:1;a:0:{}}","a:2:{i:0;a:0:{}i:1;a:0:{}}"};
        for(int i=0;i<prefix.length;i++)execute(prefix[i]+"$ev=[];set_error_handler(function($n,$s)use(&$ev){$ev[]=[$n,$s];return true;});$r=$GLOBALS[\"copy\"]??trigger_error(\"alternate\");restore_error_handler();echo serialize([$r,$ev]);",expected[i]);
    }
    @Test public void addressedCvReadsCanonicalPayloadBeforeChoosingAndJoining()throws Throwable {
        for(String initial:new String[]{"null","false","[]","[9]"}) {
            String value=initial.equals("null")?"a:0:{}":initial.equals("false")?"b:0;":initial.equals("[]")?"a:0:{}":"a:1:{i:0;i:9;}";
            execute("$a="+initial+";$alias=&$a;echo serialize([$a??[]]);","a:1:{i:0;"+value+"}");
            execute("$a="+initial+";$alias=&$a;echo serialize([$a?:[]]);",initial.equals("[9]")?"a:1:{i:0;a:1:{i:0;i:9;}}":"a:1:{i:0;a:0:{}}");
            execute("$a="+initial+";$alias=&$a;echo serialize([$a?[]:null]);",initial.equals("[9]")?"a:1:{i:0;a:0:{}}":"a:1:{i:0;N;}");
        }
    }
    @Test public void exactIntegerNativeTransportUsesItsActualBodyResult()throws Throwable {
        execute("error_reporting(0);echo serialize([error_reporting(0)]);","a:1:{i:0;i:0;}");
    }
    @Test public void untrackedConditionOrSelectedCarrierRejectsBeforeEarlierEffects()throws Throwable {
        for(String source:new String[]{"echo 'effect';$v=[];$r=$v??[];return 7;","$x=[];echo 'effect';$r=$GLOBALS['x']??$x;return 7;","echo 'effect';$r=$GLOBALS['x']??[$GLOBALS['y']];return 7;","echo 'effect';error_reporting(null);return 7;","echo 'effect';error_reporting(0.5);return 7;","echo 'effect';$mask=$GLOBALS['mask'];error_reporting($mask);return 7;"})
            for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
                var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
                var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter()) {
                    try{var rejected=assertThrows(Throwable.class,()->authority.registerModule(module,module.getData(),(p,m,b)->{}));assertTrue(rejected.toString(),rejected instanceof IOException||rejected instanceof IllegalArgumentException);assertEquals(0,output.size());assertEquals(0,domain.graph().liveNodes());}
                    finally{env.doFinal();authority.close();}
                }
            }
    }
}