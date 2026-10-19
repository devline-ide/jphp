package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.List;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpAttribute;
import php.runtime.ext.core.reflection.ReflectionClass;
import php.runtime.exceptions.CustomErrorException;
import php.runtime.lang.exception.BaseError;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;

public class PhpAttributeNativeProfileTest extends JvmCompilerCase {
    @Test public void modernBuiltinProvidersDoNotReplaceLegacyUserDeclarations() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            assertNull(env.fetchClass("Override",false));assertNull(env.fetchClass("SensitiveParameterValue",false));
            var context=new Context("class Override {} class SensitiveParameterValue {}");
            context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var module=new JvmCompiler(env,context).compile(false);
            env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
            var oldOverride=env.fetchClass("Override",false);var oldValue=env.fetchClass("SensitiveParameterValue",false);
            assertSame(module.findClass("Override"),oldOverride);assertSame(module.findClass("SensitiveParameterValue"),oldValue);
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var modernOverride=env.fetchClass("Override",false);var modernValue=env.fetchClass("SensitiveParameterValue",false);
            assertEquals(php.runtime.ext.core.classes.PhpOverrideAttribute.class,modernOverride.getNativeClass());
            assertEquals(php.runtime.ext.core.classes.PhpSensitiveParameterValue.class,modernValue.getNativeClass());
            assertNotSame(oldOverride,modernOverride);assertNotSame(oldValue,modernValue);
            var marker=modernOverride.getPhpAttributes().getFirst();
            assertEquals("Attribute",marker.name());assertEquals(12,marker.evaluateArguments(env).valueOfIndex(0).toInteger());
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            assertSame(oldOverride,env.fetchClass("Override",false));assertSame(oldValue,env.fetchClass("SensitiveParameterValue",false));
        } finally {env.doFinal();}
    }
    @Test public void legacyReflectionCloneKeepsFatalErrorWhileModernCloneIsCatchable() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var reflection=new ReflectionClass(env,env.fetchClass("ReflectionClass",false));
            try {reflection.__clone(env);fail("Legacy clone accepted");}
            catch(CustomErrorException expected) {assertEquals("Trying to clone an uncloneable object of class ReflectionClass",expected.getMessage());}
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            try {reflection.__clone(env);fail("Modern clone accepted");}
            catch(BaseError expected) {assertEquals(BaseError.class,expected.getClass());assertEquals("Trying to clone an uncloneable object of class ReflectionClass",expected.getMessage(env).toString());}
        } finally {env.doFinal();}
    }
    @Test public void nativeDeclarationProducerCannotBeEmbeddedInSourceBytecode() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var context=new Context("class SourceAttributeMetadata {}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            module.findClass("SourceAttributeMetadata").setPhpAttributes(List.of(new PhpAttribute("Attribute",new TraceInfo(context),PhpAttribute.CLASS,false,
                    List.of(PhpAttribute.Argument.nativeInteger(1)))));
            try {new ModuleDumper(context,env,true).save(module,new ByteArrayOutputStream());fail("Foreign native producer entered PHB");}
            catch(DumpException expected) {assertEquals("Native declaration arguments cannot enter source attribute metadata",expected.getMessage());}
        } finally {env.doFinal();}
    }
}
