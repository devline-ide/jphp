package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ClassDumper;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.reflection.*;

/** Real PHP autoloader effects and declaration prototypes; no fake parent provider. */
public class PhpColdParentCodecTest extends JvmCompilerCase {
    private Environment env(boolean debug,PhpLanguageProfile profile,ByteArrayOutputStream output) {
        var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(profile);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());return new Environment(scope,output);
    }
    private static ModuleEntity include(Environment env,PhpLanguageProfile profile,String code) throws Throwable {
        var ctx=new Context(code);ctx.setPhpLanguageProfile(profile);var module=new JvmCompiler(env,ctx).compile(false);
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);return module;
    }
    private byte[] saved(Environment writer,PhpLanguageProfile profile,boolean debug,String code,String name) throws Throwable {
        var ctx=new Context(code);ctx.setPhpLanguageProfile(profile);var module=new JvmCompiler(writer,ctx).compile(false);
        var c=module.findClass(name);assertNotNull(name,c);var bytes=new ByteArrayOutputStream();new ClassDumper(ctx,module,writer,debug).save(c,bytes);return bytes.toByteArray();
    }
    private static int footer(byte[] bytes,int version) {
        int result=-1;for(int i=0;i+8<=bytes.length;i++)if(ByteBuffer.wrap(bytes,i,4).getInt()==bytes.length-i-4&&ByteBuffer.wrap(bytes,i+4,4).getInt()==version){assertEquals(-1,result);result=i;}
        assertTrue("Actual footer",result>=0);return result;
    }
    private static String quote(String value){return "'"+value.replace("\\","\\\\").replace("'","\\'")+"'";}
    private void autoloader(Environment env,PhpLanguageProfile profile,String parentCode) throws Throwable {
        include(env,profile,"spl_autoload_register(function($name){echo 'autoload:'.$name.'|';eval("+quote(parentCode)+");});");
    }
    private static ModuleEntity emptyModule(PhpLanguageProfile profile){var m=new ModuleEntity(new Context("cold parent reader"));m.setPhpLanguageProfile(profile);return m;}
    private static void rejects(ClassDumper dumper,byte[] bytes,String fragment) throws Throwable {
        try{dumper.load(new ByteArrayInputStream(bytes));fail("Accepted "+fragment);}catch(DumpException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(fragment));}
    }
    @Test public void malformedOrdinaryFooterNeverRunsTheRealParentAutoloader() throws Throwable {
        for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true}) {
            var writer=env(debug,profile,new ByteArrayOutputStream());var output=new ByteArrayOutputStream();var reader=env(debug,profile,output);
            try {
                byte[] valid=saved(writer,profile,debug,"class ExternalParent{} class ColdChild extends ExternalParent{}","ColdChild");
                var malformed=valid.clone();ByteBuffer.wrap(malformed).putInt(footer(valid,7)+4,10);
                autoloader(reader,profile,"class ExternalParent{}");var dumper=new ClassDumper(new Context("cold parent reader"),emptyModule(profile),reader,debug);
                rejects(dumper,malformed,"version");reader.flushAll();assertEquals("Rejected footer must not execute PHP autoload",0,output.size());
                assertNull("Parent was not published by autoload",reader.fetchClass("ExternalParent",false,profile));
            }finally{writer.doFinal();reader.doFinal();}
        }
    }
    @Test public void malformedReadonlyFooterNeverRunsTheRealParentAutoloader() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var profile=PhpLanguageProfile.PHP_8_5;var writer=env(debug,profile,new ByteArrayOutputStream());var output=new ByteArrayOutputStream();var reader=env(debug,profile,output);
            try {
                byte[] valid=saved(writer,profile,debug,"readonly class ExternalParent{} readonly class ColdChild extends ExternalParent{}","ColdChild");
                int f=footer(valid,8);var raw=new ByteArrayInputStream(valid,f+4,valid.length-f-4);var in=new DataInputStream(raw);
                assertEquals(8,in.readInt());in.readBoolean();in.readBoolean();in.readBoolean();in.readInt();assertFalse(in.readBoolean());assertEquals(0,in.readInt());assertEquals(0,in.readInt());
                int flag=valid.length-raw.available();var malformed=valid.clone();malformed[flag]=2;
                autoloader(reader,profile,"readonly class ExternalParent{}");var d=new ClassDumper(new Context("readonly parent reader"),emptyModule(profile),reader,debug);
                rejects(d,malformed,"readonly class flag");reader.flushAll();assertEquals(0,output.size());assertNull(reader.fetchClass("ExternalParent",false,profile));
            }finally{writer.doFinal();reader.doFinal();}
        }
    }
    @Test public void realAutoloadParentLinksBeforeOwnedOverrideAndKeepsPrototype() throws Throwable {
        for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true}) {
            var writer=env(debug,profile,new ByteArrayOutputStream());var output=new ByteArrayOutputStream();var reader=env(debug,profile,output);
            try {
                String parent="class ExternalParent{public $inherited=1;function route(){return 3;}}";
                byte[] bytes=saved(writer,profile,debug,parent+" class ColdChild extends ExternalParent{public $own=2;function route(){return 4;}}","ColdChild");
                autoloader(reader,profile,parent);var d=new ClassDumper(new Context("parent prototype reader"),emptyModule(profile),reader,debug);var child=d.load(new ByteArrayInputStream(bytes));
                var actual=reader.fetchClass("ExternalParent",false,profile);assertNotNull(actual);assertSame(actual,child.getParent());
                assertSame(actual.findProperty("inherited"),child.findProperty("inherited"));assertSame(child,child.findProperty("own").getClazz());
                assertSame(actual.findMethod("route"),child.findMethod("route").getPrototype());assertSame(child,child.findMethod("route").getClazz());
                reader.flushAll();assertEquals("autoload:ExternalParent|",output.toString(StandardCharsets.UTF_8));
            }finally{writer.doFinal();reader.doFinal();}
        }
    }
    @Test public void moduleCatalogParentPointerWinsWithoutCallingProvider() throws Throwable {
        for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=env(debug,profile,output);
            try {
                var ctx=new Context("class LocalParent{public $inherited=1;function route(){return 3;}} class ColdChild extends LocalParent{function route(){return 4;}}");ctx.setPhpLanguageProfile(profile);
                var module=new JvmCompiler(env,ctx).compile(false);var bytes=new ByteArrayOutputStream();var d=new ClassDumper(ctx,module,env,debug);d.save(module.findClass("ColdChild"),bytes);
                autoloader(env,profile,"throw new Exception('provider must not run');");var child=d.load(new ByteArrayInputStream(bytes.toByteArray()));var parent=module.findClass("LocalParent");
                assertSame(parent,child.getParent());assertSame(parent.findMethod("route"),child.findMethod("route").getPrototype());env.flushAll();assertEquals(0,output.size());
            }finally{env.doFinal();}
        }
    }
    @Test public void nativeExceptionParentRetainsActualInheritedMethod() throws Throwable {
        for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true}) {
            var env=env(debug,profile,new ByteArrayOutputStream());try {
                byte[] bytes=saved(env,profile,debug,"class ColdChild extends Exception{}","ColdChild");var d=new ClassDumper(new Context("native parent reader"),emptyModule(profile),env,debug);
                var child=d.load(new ByteArrayInputStream(bytes));var parent=env.fetchClass("Exception",false,profile);assertNotNull(parent);assertNotNull(parent.getNativeClass());
                assertSame(parent,child.getParent());assertSame(parent.findMethod("getmessage"),child.findMethod("getmessage"));
            }finally{env.doFinal();}
        }
    }
    @Test public void historicalNamedConstructorKeepsTheExactParentAlias() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var profile=PhpLanguageProfile.JPHP_LEGACY;var writer=env(debug,profile,new ByteArrayOutputStream());var output=new ByteArrayOutputStream();var reader=env(debug,profile,output);
            try {
                String parent="class LegacyParent{function LegacyParent(){}}";byte[] bytes=saved(writer,profile,debug,parent+" class ColdChild extends LegacyParent{}","ColdChild");autoloader(reader,profile,parent);
                var child=new ClassDumper(new Context("legacy parent reader"),emptyModule(profile),reader,debug).load(new ByteArrayInputStream(bytes));var actual=reader.fetchClass("LegacyParent",false,profile);
                assertSame(actual,child.getParent());assertSame(actual.findMethod("legacyparent"),child.findMethod("__construct"));assertSame(actual.findMethod("__construct"),child.findMethod("__construct"));
                reader.flushAll();assertEquals("autoload:LegacyParent|",output.toString(StandardCharsets.UTF_8));
            }finally{writer.doFinal();reader.doFinal();}
        }
    }
}
