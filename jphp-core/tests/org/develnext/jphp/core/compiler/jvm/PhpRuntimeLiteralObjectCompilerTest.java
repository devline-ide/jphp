package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.BaseObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import static org.junit.Assert.*;

/** Generated NEW owns the actual object and releases its real typed property sources. */
public class PhpRuntimeLiteralObjectCompilerTest extends JvmCompilerCase {
    private ModuleEntity compile(Environment env,String source,boolean dumped) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dumped) {
            var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
            module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void actualNewResultOwnsTypedDefaultUntilItsLastReturnedEdgeSourceAndDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,(strict?"declare(strict_types=1);":"")+"class RInt {public int $v=1;} return new RInt;",dumped);
                var entity=module.findClass("RInt");assertFalse(entity.isRuntimeStateInitialized(env));
                var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});assertFalse(entity.isRuntimeStateInitialized(env));
                    ReferenceMemory physical;
                    try(var result=authority.include(module)) {
                        assertTrue(entity.isRuntimeStateInitialized(env));var object=(BaseObject)((ObjectMemory)result.raw()).value;
                        assertSame(entity,object.getReflection());assertSame(entity.getNativeClass(),object.getClass());
                        assertNotNull(object.getOwnershipObject());physical=object.getProperties().getByScalar("v");
                        assertEquals(1,physical.toLong());assertTrue(physical.hasPropertySources());assertTrue(domain.graph().liveNodes()>0);
                    }
                    assertFalse(physical.hasPropertySources());assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void twoActualSitesShareValidatedPlanAndReleaseInheritedDefaultsSourceAndDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,"class RIntParent {public int $v=1;} class RInt extends RIntParent {public ?string $s=null;public int $u;} $first=new RInt;unset($first);return new RInt;",dumped);
                var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    var member=module.getNativeMethod();
                    var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                    assertEquals(2,plan.producers().stream().filter(p->p.kind().equals("OBJECT_NEW")).count());
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    ReferenceMemory physical;
                    try(var result=authority.include(module)) {
                        var object=(BaseObject)((ObjectMemory)result.raw()).value;physical=object.getProperties().getByScalar("v");
                        assertTrue(physical.hasPropertySources());assertEquals(1,physical.toLong());
                        assertTrue(object.getProperties().getByScalar("s").isNull());assertTrue(object.getProperties().getByScalar("u").isUndefined());
                    }
                    assertFalse(physical.hasPropertySources());assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void changedRawDefaultRejectsBeforeBodyInitializationAndOutputSourceAndDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,"class RInt {public int $v=1;} echo 'effect';return new RInt;",dumped);
                var entity=module.findClass("RInt");var property=entity.findProperty("v");Memory original=property.getDefaultValue();
                var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});property.setDefaultValue(new LongMemory(2));
                    try(var result=authority.include(module)){fail("Changed default reached body");}
                    catch(IllegalArgumentException|IllegalStateException expected){}
                    env.getDefaultBuffer().flush();assertEquals(0,output.size());assertFalse(entity.isRuntimeStateInitialized(env));
                    assertEquals(0,domain.graph().liveNodes());property.setDefaultValue(original);
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void actualNewWithoutAuthorityKeepsExistingPhpConstructionSourceAndDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var module=compile(env,"class RInt {public int $v=1;} return new RInt;",dumped);
                var object=(BaseObject)((ObjectMemory)module.include(env)).value;
                assertSame(module.findClass("RInt"),object.getReflection());assertEquals(1,object.getProperties().getByScalar("v").toLong());
                assertNull(object.getOwnershipObject());
            }finally{env.doFinal();}
        }
    }
}
