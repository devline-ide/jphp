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

/** A literal ENTRY must use the actual array publisher after a reentrant diagnostic callback. */
public class PhpRuntimeScalarEntryTest extends JvmCompilerCase {
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
                    Set<java.lang.reflect.Method> registered=new HashSet<>();var dependencies=new ArrayList<>(plan.nativeMembers());
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
    private void original(String name)throws Throwable {
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_array_warning_storage_owners.tsv")) {
            assertNotNull(input);String[] selected=null;
            for(String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R"))if(line.startsWith(name+"\t"))selected=line.split("\\t",-1);
            assertNotNull(name,selected);assertEquals(3,selected.length);var decoder=Base64.getDecoder();
            String source=new String(decoder.decode(selected[1]),StandardCharsets.UTF_8),expected=new String(decoder.decode(selected[2]),StandardCharsets.UTF_8);
            execute(source.substring(5),expected);execute("declare(strict_types=1);"+source.substring(5),expected);
        }
    }
    @Test public void originalCopyKeepsTheWarningFetchBackingAfterMarkerMutation()throws Throwable {original("copy_then_mutate_original");}
    @Test public void originalMutationWithoutCopyRetiresTheWarningFetchBacking()throws Throwable {original("mutate_original_without_copy");}
    @Test public void originalUnsetCopyReleasesTheWarningFetchBacking()throws Throwable {original("copy_mutate_then_unset_copy");}
    @Test public void scalarEntryUsesCopyOnWriteAndExistingCellAddresses()throws Throwable {
        execute("$a=[];$copy=$a;$a['k']=9;echo serialize([$a,$copy]);","a:2:{i:0;a:1:{s:1:\"k\";i:9;}i:1;a:0:{}}");
        execute("$a=[];$alias=&$a;$a[]=9;echo serialize([$a,$alias]);","a:2:{i:0;a:1:{i:0;i:9;}i:1;a:1:{i:0;i:9;}}");
        execute("$a=[];$v=&$a['k'];$a['k']=9;echo serialize([$a,$v]);","a:2:{i:0;a:1:{s:1:\"k\";i:9;}i:1;i:9;}");
        execute("$a=[];$copy=$a;$v=&$a['k'];$a['k']=9;echo serialize([$a,$copy,$v]);","a:3:{i:0;a:1:{s:1:\"k\";i:9;}i:1;a:0:{}i:2;i:9;}");
    }
    @Test public void unresolvedScalarConsumersAreRejectedBeforeEarlierEffects()throws Throwable {
        for(String statement:new String[]{"$r=($a['k']=9);","$a['k']=1.5;","$a['k']+=9;"})
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
            var module=compile(env,"echo 'effect';$a=[];"+statement+"return 7;",dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try{var failure=assertThrows(Throwable.class,()->authority.registerModule(module,module.getData(),(p,m,b)->{}));assertTrue(failure.toString(),failure instanceof IOException||failure instanceof IllegalArgumentException);assertEquals(0,output.size());assertEquals(0,domain.graph().liveNodes());}
                finally{env.doFinal();authority.close();}
            }
        }
    }
}
