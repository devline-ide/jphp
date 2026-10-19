package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.PhpObjectShellVerifier;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Actual loaded-shell scalar initialization, without admitting arbitrary NEW expressions. */
public class PhpLiteralObjectDefaultsPlanTest {
    private Environment environment(boolean debug) {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
        return new Environment(scope,new ByteArrayOutputStream());
    }
    private ClassEntity load(Environment env,String source,String name,boolean dumped) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if (dumped) {var dumper=new ModuleDumper(context,env,true);var out=new ByteArrayOutputStream();dumper.save(module,out);module=dumper.load(new ByteArrayInputStream(out.toByteArray()));}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
        return module.getClasses().stream().filter(value->value.getName().equals(name)).findFirst().orElseThrow();
    }
    private void reject(Runnable action) {
        try {action.run();fail("Incomplete literal plan accepted");}catch(IllegalArgumentException|IllegalStateException expected){}
    }
    @Test public void actualRIntAndInheritedScalarDefaultsInitializeOnlyAfterPurePlanSourceAndDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var env=environment(debug);
            try {
                var entity=load(env,"class RIntParent {public int $v=1;} class RInt extends RIntParent {public float $f=2;public ?string $s=null;public int $u;}","RInt",dumped);
                assertFalse(entity.isRuntimeStateInitialized(env));
                String descriptor=PhpObjectShellVerifier.fingerprintLiteralDefaults(entity);
                var plan=PhpObjectShellVerifier.prepareLiteralDefaults(entity,env);assertEquals(descriptor,plan.fingerprint());
                assertFalse(entity.isRuntimeStateInitialized(env));assertEquals(64,plan.fingerprint().length());
                var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
                try(var execution=participant.enter()) {
                    var arrays=new PhpOwnershipArrays(domain.heap());var objects=new PhpOwnershipObjects(domain.heap(),arrays);
                    var provider=plan.initializeAndRegister(objects);assertTrue(entity.isRuntimeStateInitialized(env));
                    assertSame(provider,plan.initializeAndRegister(objects));
                    try(var allocation=objects.allocate(provider,env,plan.certifiedDefaults(objects))) {
                        assertEquals(1,allocation.object().getProperties().getByScalar("v").toLong());
                        assertEquals(2,allocation.object().getProperties().getByScalar("f").toDouble(),0);
                        assertTrue(allocation.object().getProperties().getByScalar("s").isNull());
                        assertTrue(allocation.object().getProperties().getByScalar("u").isUndefined());
                        assertSame(entity.getNativeClass(),allocation.object().getClass());
                        assertSame(allocation.certificate(),((php.runtime.lang.BaseObject)allocation.object()).getOwnershipObject());
                    }
                    assertEquals(0,domain.graph().liveNodes());participant.close();
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void mutableRawValueIdentityTypeAndDescriptorChangesRejectBeforeInitialization() throws Throwable {
        var env=environment(false);
        try {
            var entity=load(env,"class LiteralMutation {public int $v=1;}","LiteralMutation",false);var property=entity.findProperty("v");
            var raw=new LongMemory(1);property.setDefaultValue(raw);var plan=PhpObjectShellVerifier.prepareLiteralDefaults(entity,env);
            raw.value=2;reject(plan::validate);raw.value=1;plan.validate();
            property.setDefaultValue(new LongMemory(1));reject(plan::validate);property.setDefaultValue(raw);
            var type=property.getDeclaredType();property.setDeclaredType(new PhpTypeDeclaration(List.of(List.of("int"))));reject(plan::validate);property.setDeclaredType(type);
            property.setFinal(true);reject(plan::validate);property.setFinal(false);plan.validate();
            entity.methodConstruct=new MethodEntity(new Context(""));reject(plan::validate);entity.methodConstruct=null;
            byte[] bytes=entity.getData();byte[] changed=bytes.clone();changed[changed.length-1]^=1;entity.setData(changed);reject(plan::validate);entity.setData(bytes);
            var constructorField=ClassEntity.class.getDeclaredField("nativeConstructor");constructorField.setAccessible(true);
            var actualConstructor=entity.getOwnershipNativeConstructor();
            constructorField.set(entity,php.runtime.lang.BaseObject.class.getConstructor(Environment.class,ClassEntity.class));
            reject(plan::validate);constructorField.set(entity,actualConstructor);plan.validate();
            assertFalse(entity.isRuntimeStateInitialized(env));
        }finally{env.doFinal();}
    }
    @Test public void heapDeferredSubclassAndClassTypeDefaultsRejectWithoutEffects() throws Throwable {
        var env=environment(false);
        try {
            var entity=load(env,"class LiteralFrontier {public $v=1;}","LiteralFrontier",false);var property=entity.findProperty("v");Memory raw=property.getDefaultValue();
            for(Memory invalid:new Memory[]{null,new ArrayMemory(),new LongMemory(1){},Memory.UNDEFINED}) {
                property.setDefaultValue(invalid);reject(()->PhpObjectShellVerifier.prepareLiteralDefaults(entity,env));assertFalse(entity.isRuntimeStateInitialized(env));
            }
            property.setDefaultValue(raw);property.setDeclaredType(new PhpTypeDeclaration(List.of(List.of("UnknownLiteralClass"))));
            reject(()->PhpObjectShellVerifier.prepareLiteralDefaults(entity,env));assertFalse(entity.isRuntimeStateInitialized(env));
        }finally{env.doFinal();}
    }
    @Test public void planNeverAdoptsForeignProviderAndAllocationRevalidatesCurrentDefaults() throws Throwable {
        var env=environment(false);
        try {
            var entity=load(env,"class LiteralProvider {public int $v=1;}","LiteralProvider",false);
            var plan=PhpObjectShellVerifier.prepareLiteralDefaults(entity,env);var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
            try(var execution=participant.enter()) {
                var arrays=new PhpOwnershipArrays(domain.heap());
                var wrongParticipant=domain.participant(new Object());
                try(var wrongExecution=wrongParticipant.enter()) {reject(()->plan.initializeAndRegister(new PhpOwnershipObjects(domain.heap(),arrays)));}
                assertFalse(entity.isRuntimeStateInitialized(env));var foreign=new PhpOwnershipObjects(domain.heap(),arrays);
                entity.initializeRuntimeState(env);PhpObjectShellVerifier.register(foreign,env,entity);reject(()->plan.initializeAndRegister(foreign));reject(()->plan.certifiedDefaults(foreign));
                var own=new PhpOwnershipObjects(domain.heap(),arrays);var provider=plan.initializeAndRegister(own);var defaults=plan.certifiedDefaults(own);
                var property=entity.findProperty("v");Memory old=property.getDefaultValue();property.setDefaultValue(new LongMemory(2));
                try {own.allocate(provider,env,defaults);fail("Changed default reached actual allocation");}catch(IllegalStateException expected){}
                assertEquals(0,domain.graph().liveNodes());property.setDefaultValue(old);participant.close();
            }
        }finally{env.doFinal();}
    }
    @Test public void unpairedSurrogateDefaultMutationRejectsActualNewBeforeCallerEffects() throws Throwable {
        String original=String.valueOf((char)0xd800),changed=String.valueOf((char)0xd801);
        assertArrayEquals(original.getBytes(java.nio.charset.StandardCharsets.UTF_8),changed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var env=environment(debug);
            try {
                var context=new Context("class SurrogateNew {public string $v='"+original+"';} return new SurrogateNew;");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped) {var dumper=new ModuleDumper(context,env,true);var out=new ByteArrayOutputStream();dumper.save(module,out);module=dumper.load(new ByteArrayInputStream(out.toByteArray()));}
                env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);
                var entity=module.getClasses().stream().filter(value->value.getName().equals("SurrogateNew")).findFirst().orElseThrow();
                var property=entity.findProperty("v");assertEquals(original,property.getDefaultValue().toString());
                String fingerprint=PhpObjectShellVerifier.fingerprintLiteralDefaults(entity);
                property.setDefaultValue(new StringMemory(changed));
                assertNotEquals(fingerprint,PhpObjectShellVerifier.fingerprintLiteralDefaults(entity));
                assertFalse(entity.isRuntimeStateInitialized(env));
                var loadedClass=env.fetchClass("SurrogateNew",false);var domain=new PhpOwnershipDomain();
                try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
                    authority.registerModule(module,module.getData(),(plan,member,bytes)->{});
                    try {authority.include(module);fail("Changed UTF-16 default reached actual NEW");}catch(IllegalArgumentException expected){}
                    assertFalse(entity.isRuntimeStateInitialized(env));assertSame(loadedClass,env.fetchClass("SurrogateNew",false));
                    assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
}
