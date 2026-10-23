package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.exceptions.CustomErrorException;
import php.runtime.lang.exception.BaseError;
import php.runtime.loader.dump.ModuleDumper;

public class PhpAttributeFunctionalTest extends JvmCompilerCase {
    @Test public void overrideRejectsMissingMethodPropertyPrivateParentAndTraitAliasBeforeBody() throws Throwable {
        String[][] controls={
            {"class C {#[Override] function missing(){}}", "C::missing() has #[\\Override] attribute, but no matching parent method exists"},
            {"class C {#[Override] public int $missing=1;}","C::$missing has #[\\Override] attribute, but no matching parent property exists"},
            {"class P {private function missing(){}} class C extends P {#[Override] public function missing(){}}","C::missing() has #[\\Override] attribute, but no matching parent method exists"},
            {"class C {#[DelayedTargetValidation,Override] function missing(){}}","C::missing() has #[\\Override] attribute, but no matching parent method exists"},
            {"class P {function m(){}} trait T {#[Override] function m(){}} class C extends P {use T {m as alias;}}","C::alias() has #[\\Override] attribute, but no matching parent method exists"}
        };
        for(var row:controls) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);
            try {
                var context=new Context(row[0]+" $GLOBALS['entered']=1;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {
                    var module=new JvmCompiler(env,context).compile(false);
                    if(isCompiled()) {var dumper=new ModuleDumper(context,env,false);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                    module.include(env);fail("Accepted missing Override relation");
                }
                catch(CustomErrorException expected) {assertEquals(row[1],expected.getMessage());}
                assertNull(env.getGlobals().getByScalar("entered"));assertEquals(0,output.size());
            }finally {env.doFinal();}
        }
    }
    @Test public void sensitiveParameterExceptionAndDebugTracesMatchOriginalNativeBytes() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);
            try(var fixture=PhpNativeTestResources.fixture("attributes/original","sensitive-trace.php","sensitive-trace.php","helper.php")) {
                env.getScope().setDebugMode(debug);env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                var stderr=new ByteArrayOutputStream();
                assertTrue(env.defineConstant("STDERR",php.runtime.memory.ObjectMemory.valueOf(
                        new php.runtime.ext.core.classes.stream.MiscStream(env,stderr)),true));
                var file=fixture.file();
                byte[] original=PhpNativeTestResources.read("attributes/original/sensitive-trace.php");
                try(var resource=getClass().getClassLoader().getResourceAsStream("resources/attributes/original/sensitive-trace.php")) {
                    assertNotNull(resource);assertArrayEquals(resource.readAllBytes(),original);
                }
                var context=new Context(new String(original,StandardCharsets.UTF_8),file);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(isCompiled()) {var dumper=new ModuleDumper(context,env,debug);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("native sensitive trace debug="+debug+" PHB="+isCompiled()+" actual="+output.toString(StandardCharsets.UTF_8),
                        PhpNativeTestResources.read("attributes/original/sensitive-trace.stdout"),output.toByteArray());
                assertArrayEquals("native stderr debug="+debug+" PHB="+isCompiled()+" actual="+stderr.toString(StandardCharsets.UTF_8),
                        PhpNativeTestResources.read("attributes/original/sensitive-trace.stderr"),stderr.toByteArray());
            }finally {env.doFinal();}
        }
    }
    @Test public void sensitiveValueHasActualPrivateReadonlyMixedPropertyAndCannotBeReinitialized() throws Throwable {
        var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);
        try {
            var entity=env.getScope().fetchPhp85Class("SensitiveParameterValue");var property=entity.findProperty("value");
            assertTrue(property.isPrivate());assertTrue(property.isPhpReadonly());assertFalse(property.isDefault());
            assertEquals("mixed",property.getDeclaredType().getSignature());
            assertSame(php.runtime.Memory.UNDEFINED,property.getDefaultValue());
            var context=new Context("$value=new SensitiveParameterValue(null); try{$value->__construct('replacement');}catch(Error $e){echo $e->getMessage();} echo '|'; var_dump($value->getValue()); $array=new SensitiveParameterValue(['x'=>1,'nested'=>['y'=>3]]); $copy=$array->getValue(); $copy['x']=2; $copy['nested']['y']=4; $original=$array->getValue(); echo '|array=',$original['x'],'/',$copy['x'],'/',$original['nested']['y'],'/',$copy['nested']['y']; $object=new stdClass(); $object->x=1; $box=new SensitiveParameterValue($object); $returned=$box->getValue(); echo '|object=',($returned===$object?'same':'different'); $returned->x=3; echo '/',$object->x;");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(isCompiled()) {var dumper=new ModuleDumper(context,env,false);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
            assertEquals("Cannot modify readonly property SensitiveParameterValue::$value|NULL\n|array=1/2/3/4|object=same/3",output.toString(StandardCharsets.UTF_8));
            var value=new php.runtime.ext.core.classes.PhpSensitiveParameterValue(env,php.runtime.Memory.NULL);
            assertTrue(value.getValue().isNull());
            try {value.__construct(env,php.runtime.memory.StringMemory.valueOf("replacement"));fail("Readonly value replaced");}
            catch(BaseError expected) {assertEquals("Cannot modify readonly property SensitiveParameterValue::$value",expected.getMessage(env).toString());}
            assertTrue(value.getValue().isNull());
        }finally {env.doFinal();}
    }
}
