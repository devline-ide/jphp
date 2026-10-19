package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.exceptions.CustomErrorException;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;

public class PhpAllowDynamicPropertiesTest extends JvmCompilerCase {
    private Environment env(ByteArrayOutputStream output,boolean debug) {
        var scope=newScope();scope.setDebugMode(debug);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope,output);
    }
    private static php.runtime.reflection.ModuleEntity compile(Environment env,Context context,boolean dump,boolean debug) throws Throwable {
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
        if(dump){var dumper=new ModuleDumper(context,env,debug);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        return module;
    }
    private static void publish(Environment env,php.runtime.reflection.ModuleEntity module) {
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
    }
    @Test public void nativeParentChildNamespacesArgumentsAndDelayedValidationMatchExactPhpBytes() throws Throwable {
        for(String name:new String[]{"behavior","namespaces","delayed","lazy-argument"})
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=env(output,debug);
            try(var fixture=PhpNativeTestResources.fixture("allow-dynamic",name+".php",name+".php")) {
                byte[] original=PhpNativeTestResources.read("allow-dynamic/"+name+".php");
                var context=new Context(new String(original,StandardCharsets.UTF_8),fixture.file());
                var module=compile(env,context,dumped,debug);
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                publish(env,module);module.include(env);env.flushAll();
                assertArrayEquals(name+" debug="+debug+" PHB="+dumped+" actual="+output.toString(StandardCharsets.UTF_8),PhpNativeTestResources.read("allow-dynamic/"+name+".stdout"),output.toByteArray());
                assertEquals("native fixture must have no hidden stderr",0,PhpNativeTestResources.read("allow-dynamic/"+name+".stderr").length);
            } finally {env.doFinal();}
        }
    }
    @Test public void nativeIllegalTargetsAndRepeatedDeclarationsFailBeforeBody() throws Throwable {
        for(String name:new String[]{"negative-trait","negative-interface","negative-readonly","negative-enum","negative-function","negative-property","repeat"}) {
            var output=new ByteArrayOutputStream();var env=env(output,true);
            try(var fixture=PhpNativeTestResources.fixture("allow-dynamic",name+".php",name+".php")) {
                String stderr=PhpNativeTestResources.text("allow-dynamic/"+name+".stderr");
                String nativeContextName=PhpNativeTestResources.text("allow-dynamic/"+name+".context-name");
                String prefix="Fatal error: ";int from=stderr.indexOf(prefix),to=stderr.indexOf(" in "+nativeContextName,from);
                assertTrue("exact native fatal message",from>=0&&to>from);
                String expected=stderr.substring(from+prefix.length(),to);
                var context=new Context(PhpNativeTestResources.text("allow-dynamic/"+name+".php"),fixture.file());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try{new JvmCompiler(env,context).compile(false);fail("Accepted "+name);}
                catch(CustomErrorException rejected){assertEquals(expected,rejected.getMessage());}
                assertEquals("no PHP body executed",0,output.size());
            } finally {env.doFinal();}
        }
    }
    @Test public void profileNativeProviderKeepsLegacyUserClassAndCreationPolicy() throws Throwable {
        var output=new ByteArrayOutputStream();var env=env(output,false);
        try {
            assertNull(env.fetchClass("AllowDynamicProperties",false,PhpLanguageProfile.JPHP_LEGACY));
            var legacy=new Context("class AllowDynamicProperties {} class LegacyDynamic {} $x=new LegacyDynamic; $x->extra=3; echo $x->extra;");
            legacy.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);var old=new JvmCompiler(env,legacy).compile(false);publish(env,old);old.include(env);env.flushAll();
            var oldClass=env.fetchClass("AllowDynamicProperties",false,PhpLanguageProfile.JPHP_LEGACY);assertSame(old.findClass("AllowDynamicProperties"),oldClass);
            var nativeClass=env.fetchClass("AllowDynamicProperties",false,PhpLanguageProfile.PHP_8_5);
            assertEquals(php.runtime.ext.core.classes.PhpAllowDynamicPropertiesAttribute.class,nativeClass.getNativeClass());assertNotSame(oldClass,nativeClass);
            assertEquals(1,nativeClass.getPhpAttributes().getFirst().evaluateArguments(env).valueOfIndex(0).toInteger());
            assertSame(oldClass,env.fetchClass("AllowDynamicProperties",false,PhpLanguageProfile.JPHP_LEGACY));assertEquals("3",output.toString(StandardCharsets.UTF_8));
        } finally {env.doFinal();}
    }
    @Test public void forgedReadonlyAttributeIsRejectedBeforeRealAutoloadConstructorAndBody() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var writer=env(new ByteArrayOutputStream(),debug);var output=new ByteArrayOutputStream();var reader=env(output,debug);
            try {
                var context=new Context("#[AllowDynamicProperties] class A {} readonly class R extends ExternalParent {function __construct(){echo 'constructor';}} echo 'body';");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(writer,context).compile(false);
                var bytes=new ByteArrayOutputStream();new ModuleDumper(context,writer,debug).save(module,bytes);byte[] corrupt=bytes.toByteArray();
                byte[] identity="C/A".getBytes(StandardCharsets.UTF_8);int found=-1;
                for(int at=0;at+identity.length<=corrupt.length;at++)if(corrupt[at]==identity[0]&&corrupt[at+1]==identity[1]&&corrupt[at+2]==identity[2]){assertEquals("unique exact ATMD identity",-1,found);found=at;}
                assertTrue(found>=0);corrupt[found+2]='R';
                var loader=new Context("spl_autoload_register(function($name){echo 'autoload:'.$name;eval('readonly class ExternalParent{}');});");
                var setup=compile(reader,loader,false,debug);publish(reader,setup);setup.include(reader);reader.flushAll();assertEquals(0,output.size());
                int published=reader.getScope().moduleIndexMap.size();
                try{new ModuleDumper(context,reader,debug).load(new ByteArrayInputStream(corrupt));fail("Forged readonly AllowDynamicProperties accepted");}
                catch(DumpException rejected){assertEquals("Cannot apply #[\\AllowDynamicProperties] to readonly class R",rejected.getMessage());}
                reader.flushAll();assertEquals("no real autoload, constructor or module body",0,output.size());
                assertNull(reader.fetchClass("ExternalParent",false,PhpLanguageProfile.PHP_8_5));assertEquals(published,reader.getScope().moduleIndexMap.size());
            } finally {writer.doFinal();reader.doFinal();}
        }
    }
}
