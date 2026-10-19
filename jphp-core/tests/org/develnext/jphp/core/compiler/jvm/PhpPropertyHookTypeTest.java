package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.classes.PhpPropertyHookType;
import php.runtime.lang.BaseEnum;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpPropertyHooks;
import static org.junit.Assert.*;

/** Actual native string-backed enum declarations, per-Environment cases and original PHP native hook-reflection fixtures. */
public class PhpPropertyHookTypeTest extends JvmCompilerCase {
    private byte[] resource(String name)throws IOException {
        try(var in=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,in);return in.readAllBytes();}
    }
    @Test public void nativeDeclarationUsesActualBaseEnumCasesAndProfileIsolation()throws Throwable {
        var scope=newScope();var first=new Environment(scope);var second=new Environment(scope);
        try {
            assertNull(first.fetchClass("PropertyHookType",false,PhpLanguageProfile.JPHP_LEGACY));
            ClassEntity declared=first.fetchClass("PropertyHookType",true,PhpLanguageProfile.PHP_8_5);
            assertSame(PhpPropertyHookType.class,declared.getNativeClass());assertTrue(declared.isInternal());assertTrue(declared.isEnum());assertTrue(declared.isFinal());
            assertNull(declared.getParent());assertNull(declared.getModule());assertEquals("string",declared.getEnumBackingType());
            assertTrue(declared.isInstanceOfAsciiName("UnitEnum"));assertTrue(declared.isInstanceOfAsciiName("BackedEnum"));
            assertEquals(2,declared.getConstants().size());assertEquals(List.of("Get","Set"),declared.getConstants().stream().map(ConstantEntity::getName).toList());
            MethodEntity cases=declared.findMethod("cases");assertTrue(php.runtime.PhpEnums.generatedMethod(cases));
            assertEquals("__$enum$cases",cases.getNativeMethod().getName());assertSame(PhpPropertyHookType.class,cases.getNativeMethod().getDeclaringClass());
            assertEquals("array",cases.getReturnTypeChecker().getSignature());assertEquals(0,cases.getParameters().length);
            ObjectMemory get=declared.findConstant("Get").getValue(first).toValue(ObjectMemory.class);
            ObjectMemory set=declared.findConstant("Set").getValue(first).toValue(ObjectMemory.class);
            assertTrue(get.value instanceof BaseEnum);assertEquals("Get",((BaseEnum)get.value).enumCaseName());assertEquals("Set",((BaseEnum)set.value).enumCaseName());
            assertEquals("get",((BaseEnum)get.value).enumBackingValue().toString());assertEquals("set",((BaseEnum)set.value).enumBackingValue().toString());
            PropertyEntity backing=declared.findProperty("value");assertTrue(php.runtime.PhpEnums.syntheticProperty(declared,backing));assertTrue(backing.isPhpReadonly());assertEquals("string",backing.getDeclaredType().getSignature());
            assertSame(get.value,declared.findConstant("Get").getValue(first).toValue(ObjectMemory.class).value);
            assertNotSame(get.value,declared.findConstant("Get").getValue(second).toValue(ObjectMemory.class).value);
            assertEquals(PhpPropertyHooks.Kind.GET,PhpPropertyHookType.kind(first,new ObjectMemory(get.value)));
            assertEquals(PhpPropertyHooks.Kind.SET,PhpPropertyHookType.kind(first,set));
            try{PhpPropertyHookType.kind(second,get);fail("Foreign Environment enum identity accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("canonical Environment"));}
            PropertyEntity name=declared.findProperty("name");assertTrue(php.runtime.PhpEnums.syntheticProperty(declared,name));assertTrue(name.isPhpReadonly());assertFalse(name.isDefault());
            assertEquals("Get",get.getProperties().getByScalar(name.getSpecificName()).toString());
            assertNull(first.fetchClass("PropertyHookType",false,PhpLanguageProfile.JPHP_LEGACY));
            assertFalse(Arrays.stream(PhpPropertyHookType.class.getDeclaredFields()).anyMatch(f->java.lang.reflect.Modifier.isStatic(f.getModifiers()) && (Memory.class.isAssignableFrom(f.getType()) || BaseEnum.class.isAssignableFrom(f.getType()))));
        }finally{try{first.doFinal();}finally{second.doFinal();}}
    }
    @Test public void nativeCarrierCannotBeConstructedWithoutActualActiveCaseCapability()throws Throwable {
        var env=new Environment(newScope());try {
            ClassEntity declared=env.fetchClass("PropertyHookType",true,PhpLanguageProfile.PHP_8_5);
            try{new PhpPropertyHookType(env,declared);fail("Native enum constructor lacks its active case capability");}
            catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("active Environment capability"));}
            assertEquals(2,php.runtime.PhpEnums.cases(env,"PropertyHookType").toValue(php.runtime.memory.ArrayMemory.class).size());
        }finally{env.doFinal();}
    }
    @Test public void originalNativeHookReflectionAndFinalPropertyCasesMatchSourcePhbBothDebugModes()throws Throwable {
        JsonArray rows=new Gson().fromJson(new String(resource("resources/property_hooks/hooktype/controls.json"),StandardCharsets.UTF_8),JsonArray.class);
        assertEquals(4,rows.size());List<Throwable> failures=new ArrayList<>();
        for(JsonElement element:rows)for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            JsonObject row=element.getAsJsonObject();String name=row.get("resource").getAsString();byte[] code=resource(name+".php");
            Path directory=Files.createTempDirectory("hooktype-native-fixture-"),file=directory.resolve(row.get("case").getAsString()+".php");Files.write(file,code);
            var output=new ByteArrayOutputStream();var scope=newScope();scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());var env=new Environment(scope,output);
            try {
                Context context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);ModuleEntity module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();new ModuleDumper(context,env,debug).save(module,bytes);module=new ModuleDumper(context,env,debug).load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals(row.get("id").getAsString()+" debug="+debug+" PHB="+phb,resource(name+".stdout"),output.toByteArray());assertEquals(0,resource(name+".stderr").length);
            }catch(Throwable failed){failures.add(new AssertionError(row.get("id").getAsString()+" debug="+debug+" PHB="+phb,failed));}
            finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
        }
        if(!failures.isEmpty()){var aggregate=new AssertionError(failures.size()+" original native hook reflection variants failed");failures.forEach(aggregate::addSuppressed);throw aggregate;}
    }
    @Test public void nativeEnumPhpReflectionSerializationAndNegativeControlsKeepActualContracts()throws Throwable {
        String code="""
            enum OtherHookType {case Get;}
            class HookChoice {public int $plain=1;public int $get {get=>7;}public int $set {set {}}}
            function denied($f){try{$f();throw new Exception('negative accepted');}catch(Error $e){}}
            $g=PropertyHookType::Get;$s=PropertyHookType::Set;
            if(!($g instanceof UnitEnum)||!($g instanceof BackedEnum)||$g===$s||PropertyHookType::cases()!==[$g,$s]||$g->value!=='get'||$s->value!=='set')throw new Exception('enum');
            $r=new ReflectionEnum(PropertyHookType::class);
            if(!$r->isInternal()||!$r->isEnum()||!$r->isBacked()||$r->isInstantiable()||$r->isCloneable()||(string)$r->getBackingType()!=='string')throw new Exception('reflection');
            if(array_map(fn($c)=>$c->getName(),$r->getCases())!==['Get','Set'])throw new Exception('cases');
            if(unserialize(serialize($g))!==$g||serialize($g)!=='E:20:"PropertyHookType:Get";')throw new Exception('serialization');
            if(PropertyHookType::from('get')!==$g||PropertyHookType::tryFrom('set')!==$s||PropertyHookType::tryFrom('GET')!==null)throw new Exception('backing lookup');
            foreach(['from'=>'static','tryFrom'=>'?static'] as $method=>$return){$m=new ReflectionMethod(PropertyHookType::class,$method);if((string)$m->getReturnType()!==$return||(string)$m->getParameters()[0]->getType()!=='string|int')throw new Exception('lookup signature');}
            if($r->getCase('Get')->getBackingValue()!=='get')throw new Exception('backing reflection');
            $p=new ReflectionProperty(HookChoice::class,'plain');if($p->hasHook($g)||$p->getHook($g)!==null)throw new Exception('plain');
            $p=new ReflectionProperty(HookChoice::class,'get');if(!$p->hasHook($g)||$p->hasHook($s)||$p->getHook($s)!==null||$p->getHook($g)->getName()!=='$get::get')throw new Exception('get');
            $p=new ReflectionProperty(HookChoice::class,'set');if($p->hasHook($g)||!$p->hasHook($s)||$p->getHook($s)->getName()!=='$set::set')throw new Exception('set');
            foreach([null,'Get',0,OtherHookType::Get] as $bad){try{$p->hasHook($bad);throw new Exception('wrong type');}catch(TypeError $e){}try{$p->getHook($bad);throw new Exception('wrong type');}catch(TypeError $e){}}
            denied(fn()=>new PropertyHookType);denied(fn()=>clone $g);denied(fn()=>$r->newInstanceWithoutConstructor());
            denied(function()use($g){$g->name='other';});denied(function()use($g){$g->value='other';});denied(function()use($g){$g->newField=1;});
            if($g->name!=='Get'||$g->value!=='get'||!property_exists($g,'value')||!method_exists(PropertyHookType::class,'from'))throw new Exception('backed enum state');
            echo 'ok';
            """;
        for(boolean phb:new boolean[]{false,true}){var output=new ByteArrayOutputStream();var scope=newScope();scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());var env=new Environment(scope,output);
            try {Context context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);ModuleEntity module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();new ModuleDumper(context,env,true).save(module,bytes);module=new ModuleDumper(context,env,true).load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();assertEquals("ok",output.toString(StandardCharsets.UTF_8));
            }finally{env.doFinal();}
        }
    }

    @Test public void nativeBackedLookupWeakStrictScalarPhasesMatchRealPhpSourceAndPhbBothDebugModes()throws Throwable {
        JsonArray rows=new Gson().fromJson(new String(resource("resources/property_hooks/hooktype/backing-controls.json"),StandardCharsets.UTF_8),JsonArray.class);
        assertEquals(2,rows.size());List<Throwable> failures=new ArrayList<>();
        for(JsonElement element:rows)for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            JsonObject row=element.getAsJsonObject();String name=row.get("resource").getAsString();byte[] code=resource(name+".php");
            Path directory=Files.createTempDirectory("hooktype-backing-fixture-"),file=directory.resolve(row.get("case").getAsString()+".php");Files.write(file,code);
            var output=new ByteArrayOutputStream();var scope=newScope();scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());var env=new Environment(scope,output);
            try {
                Context context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);ModuleEntity module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();new ModuleDumper(context,env,debug).save(module,bytes);module=new ModuleDumper(context,env,debug).load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals(row.get("id").getAsString()+" debug="+debug+" PHB="+phb,resource(name+".stdout"),output.toByteArray());assertEquals(0,resource(name+".stderr").length);
            }catch(Throwable failed){failures.add(new AssertionError(row.get("id").getAsString()+" debug="+debug+" PHB="+phb,failed));}
            finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
        }
        if(!failures.isEmpty()){var aggregate=new AssertionError(failures.size()+" native backing lookup variants failed");failures.forEach(aggregate::addSuppressed);throw aggregate;}
    }
}
