package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ReferenceMemory;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import static org.junit.Assert.*;

/** Actual generated FIELD references survive object disposal without stale typed Sources. */
public class PhpRuntimeDeclaredPropertyReferenceTest extends JvmCompilerCase {
    private ModuleEntity compile(Environment env,String source,boolean dumped) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dumped) {
            var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
            module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private void free(String statements,String alias,int sites) throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,(strict?"declare(strict_types=1);":"")+"class RInt {public int $v=1;} "+statements,dumped);
                var member=module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
                assertEquals(sites,plan.producers().stream().filter(p->p.kind().equals("PROPERTY_REF_RESULT")).count());
                var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)){assertEquals("free",result.raw().toString());}
                    var global=env.getGlobals().getByScalar(alias);assertNotNull(global);
                    assertTrue(global.getRawValue() instanceof ReferenceMemory);
                    var canonical=(ReferenceMemory)global.getRawValue();assertNotNull(canonical.getOwnershipCell());
                    assertEquals("free",canonical.toString());assertFalse(canonical.hasPropertySources());
                }
                assertEquals(0,domain.graph().liveNodes());
            }finally{env.doFinal();}
        }
    }
    @Test public void lastObjectSourceReleasesRealPropertyCellAcrossSourceDumpDebugAndStrict() throws Throwable {
        free("$a=new RInt;$ref=&$a->v;unset($a);$ref='free';return $ref;","ref",1);
    }
    @Test public void twoRealPropertyReferencesKeepSameCellAfterObjectSourceRelease() throws Throwable {
        free("$a=new RInt;$first=&$a->v;$second=&$a->v;unset($a);$first='free';return $second;","second",2);
    }
    @Test public void lastOfTwoActualObjectOwnersDetachesSourceWithoutReleasingCellAliases() throws Throwable {
        free("$a=new RInt;$same=$a;$ref=&$a->v;unset($a);unset($same);$ref='free';return $ref;","ref",1);
    }
    @Test public void actualLiveObjectStillConstrainsTheSameReferencedCell() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,(strict?"declare(strict_types=1);":"")+"class RInt {public int $v=1;} $a=new RInt;$ref=&$a->v;$ref='bad';return 7;",dumped);
                var authority=new PhpRuntimeOwnership.Authority(env,domain);
                ReferenceMemory canonical;
                try(var execution=authority.enter();var owned=authority) {
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});Throwable rejected=null;
                    try(var result=authority.include(module)){fail("Live typed Source disappeared before object release");}
                    catch(Throwable failure){rejected=failure;}
                    assertNotNull(rejected);assertTrue(rejected.toString(),rejected.toString().contains("TypeError"));
                    canonical=(ReferenceMemory)env.getGlobals().getByScalar("ref").getRawValue();
                    assertEquals(1,canonical.toLong());assertTrue(canonical.hasPropertySources());
                }
                assertFalse(canonical.hasPropertySources());assertEquals(0,domain.graph().liveNodes());
            }finally{env.doFinal();}
        }
    }
    @Test public void uninitializedAndNonpublicPropertiesRejectBeforeBodyEffects() throws Throwable {
        for(String declaration:new String[]{"public int $v;","private int $v=1;"})
            for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
                var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
                var domain=new PhpOwnershipDomain();
                try {
                    var module=compile(env,"class RInt {"+declaration+"} echo 'effect';$a=new RInt;$ref=&$a->v;return 7;",dumped);
                    var authority=new PhpRuntimeOwnership.Authority(env,domain);
                    try(var execution=authority.enter();var owned=authority) {
                        try {
                            authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                            try(var result=authority.include(module)){fail("Unsupported FIELD source reached body");}
                        }catch(IllegalArgumentException|IllegalStateException|IOException expected){}
                        assertEquals("",env.getDefaultBuffer().getContents().toString());assertEquals(0,output.size());
                        assertFalse(module.findClass("RInt").isRuntimeStateInitialized(env));assertEquals(0,domain.graph().liveNodes());
                    }
                }finally{env.doFinal();}
            }
    }
}
