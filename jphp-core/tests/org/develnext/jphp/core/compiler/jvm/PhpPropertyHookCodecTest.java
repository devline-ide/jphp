package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.*;
import static org.junit.Assert.*;

/** Actual source compilation and cold transport. No re-tagged synthetic PHB headers. */
public class PhpPropertyHookCodecTest extends JvmCompilerCase {
    private byte[] resource(String name)throws IOException {
        try(var in=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,in);return in.readAllBytes();}
    }
    private ModuleEntity compile(Environment env,String code)throws Throwable {
        Context context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        return new JvmCompiler(env,context).compile(false);
    }
    private ClassEntity declaration(ModuleEntity module,String name) {
        ClassEntity result=module.findClass(name);
        if(result==null)for(ClassEntity candidate:module.getDeferredClasses())if(candidate.getName().equals(name))result=candidate;
        assertNotNull(name,result);return result;
    }
    @Test public void realCompiledHookCatalogSurvivesColdModuleTransportBothDebugModes()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var sourceScope=newScope();sourceScope.setDebugMode(debug);var source=new Environment(sourceScope);
            byte[] artifact;
            try {
                ModuleEntity module=compile(source,"class WireBacked {public int $x=1 {set=>$value;get=>$this->x;}} class WireVirtual {public int $v {get=>7;}}");
                assertEquals(ModuleDumper.COMPILER_ABI_VERSION,module.getCompilerProvenance().getCompilerAbiVersion());
                var bytes=new ByteArrayOutputStream();new ModuleDumper(module.getContext(),source,debug).save(module,bytes);artifact=bytes.toByteArray();
                assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(artifact)).getCompilerAbiVersion());
            } finally {source.doFinal();}
            var cold=new Environment(newScope());
            try {
                ModuleEntity copy=new ModuleDumper(new Context(""),cold,debug).load(new ByteArrayInputStream(artifact));
                assertEquals(ModuleDumper.COMPILER_ABI_VERSION,copy.getCompilerProvenance().getCompilerAbiVersion());
                assertEquals(ModuleDumper.COMPILER_ABI_VERSION,copy.getCompilerProvenance().getAcceptedDumpAbiVersion());
                assertEquals(PhpLanguageProfile.PHP_8_5,copy.getPhpLanguageProfile());
                ClassEntity backed=declaration(copy,"WireBacked");PropertyEntity property=backed.findProperty("x");
                assertNotNull(property.getPhpHooks());assertFalse(property.getPhpHooks().isVirtual());assertTrue(property.isDefault());
                assertEquals(1,property.getDefaultValue().toLong());assertEquals("int",property.getDeclaredType().getErrorSignature());
                assertEquals(php.runtime.reflection.support.PhpPropertyHooks.Kind.SET,property.getPhpHooks().declarationOrder().get(0).kind());
                for(var hook:property.getPhpHooks().declarationOrder()) {
                    assertSame(property,hook.declaration());assertSame(backed,hook.method().getClazz());
                    assertNull(hook.method().getNativeMethod());assertNull(backed.findMethod(hook.method().getLowerName()));
                    assertEquals("$x::"+(hook.kind()==php.runtime.reflection.support.PhpPropertyHooks.Kind.GET?"get":"set"),hook.method().getName());
                }
                PropertyEntity virtual=declaration(copy,"WireVirtual").findProperty("v");
                assertTrue(virtual.getPhpHooks().isVirtual());assertFalse(virtual.isDefault());assertTrue(virtual.getDefaultValue().isUndefined());
                assertNotNull(virtual.getPhpHooks().get());assertNull(virtual.getPhpHooks().set());
            } finally {cold.doFinal();}
        }
    }
    @Test public void pinnedNativeBackedCaseExecutesFromSourceAndFreshColdPhb()throws Throwable {
        byte[] code=resource("resources/property_hooks/codec/backed_full.php"),expected=resource("resources/property_hooks/codec/backed_full.stdout");
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var scope=newScope();scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var env=new Environment(scope,output);Path dir=Files.createTempDirectory("hook-codec-native-"),file=dir.resolve("backed_full.php");Files.write(file,code);
            try {
                Context context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                ModuleEntity module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();new ModuleDumper(context,env,debug).save(module,bytes);env.doFinal();
                    scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                    scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());env=new Environment(scope,output);
                    module=new ModuleDumper(context,env,debug).load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("debug="+debug+" PHB="+phb,expected,output.toByteArray());
                assertEquals(0,resource("resources/property_hooks/codec/backed_full.stderr").length);
            } finally {try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(dir);}}
        }
    }
    @Test public void unchangedActualHistorical18Through21ArtifactsLoadWithOriginalProvenance()throws Throwable {
        JsonArray rows=new Gson().fromJson(new String(resource("resources/property_hooks/codec/historical.json"),StandardCharsets.UTF_8),JsonArray.class);
        assertEquals(4,rows.size());
        for(JsonElement element:rows){JsonObject row=element.getAsJsonObject();var env=new Environment(newScope());
            try {byte[] bytes=resource(row.get("resource").getAsString());
                assertEquals(row.get("abi").getAsInt(),ModuleDumper.readHeader(new ByteArrayInputStream(bytes)).getCompilerAbiVersion());
                ModuleEntity module=new ModuleDumper(new Context(""),env,true).load(new ByteArrayInputStream(bytes));
                assertEquals(row.get("abi").getAsInt(),module.getCompilerProvenance().getCompilerAbiVersion());
                assertEquals(row.get("abi").getAsInt(),module.getCompilerProvenance().getAcceptedDumpAbiVersion());
                assertTrue(module.hasValidatedGeneratorLinkage());
            } finally {env.doFinal();}
        }
    }
    @Test public void physicalOld18Through21ReadersRejectActual22BeforeReadingAnyBody()throws Throwable {
        byte[] actual;var env=new Environment(newScope());
        try {ModuleEntity module=compile(env,"class OldReject {public int $x=1 {get=>$this->x;}} ");var out=new ByteArrayOutputStream();new ModuleDumper(module.getContext(),env,true).save(module,out);actual=out.toByteArray();}
        finally {env.doFinal();}
        JsonArray rows=new Gson().fromJson(new String(resource("resources/property_hooks/codec/old-readers.json"),StandardCharsets.UTF_8),JsonArray.class);
        assertEquals(4,rows.size());
        for(JsonElement element:rows){JsonObject row=element.getAsJsonObject();List<URL> urls=new ArrayList<>();
            String[] names=new String[row.getAsJsonArray("classpath").size()];
            for(int at=0;at<names.length;at++)names[at]=row.getAsJsonArray("classpath").get(at).getAsJsonObject().get("resource").getAsString();
            try(var artifacts=PhpNativeTestResources.treeFixture("property-hook-old-readers",names[0],names)) {
            for(JsonElement item:row.getAsJsonArray("classpath")){JsonObject jar=item.getAsJsonObject();Path path=artifacts.sibling(jar.get("resource").getAsString());
                assertEquals(jar.get("sha256").getAsString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));urls.add(path.toUri().toURL());}
            try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())) {
                Class<?> old=Class.forName("php.runtime.loader.dump.ModuleDumper",true,loader);assertEquals(row.get("abi").getAsInt(),old.getField("COMPILER_ABI_VERSION").getInt(null));
                var input=new ByteArrayInputStream(actual);
                try {old.getMethod("readHeader",InputStream.class).invoke(null,input);fail("Old physical reader accepted ABI22");}
                catch(InvocationTargetException rejected){assertEquals("php.runtime.loader.dump.io.DumpException",rejected.getCause().getClass().getName());assertEquals("Unsupported JPHP compiler ABI: "+ModuleDumper.COMPILER_ABI_VERSION,rejected.getCause().getMessage());}
                assertEquals("Rejected immediately after real header ABI",12,actual.length-input.available());
            }
            }
        }
    }
}
