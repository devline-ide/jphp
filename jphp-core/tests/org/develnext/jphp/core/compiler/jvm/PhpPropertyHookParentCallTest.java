package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.classes.stream.MiscStream;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** Exact pinned PHP8.5.11 parent operation controls; source/PHB use the same native bytes. */
public class PhpPropertyHookParentCallTest extends JvmCompilerCase {
    private static final String KIT="property-hook-parent";
    private static final Map<String,String> SOURCE_PINS=Map.of(
            "cross-kind","2b180a0a29065a85c1b3a43d0e258d7ab2d362fd132ef2d8253b60c2cd051add",
            "get-acquisition","5d33cd20fb9a8869a052455ff17801c34a1c88cadd086b078607e1ed36121996",
            "outside-hook","39718fd4cdb5e47cd03e5e889764fe08bb691249c61e4147fc55dff8e5cba5c3",
            "runtime-errors-strict","0137a69b709531b093e1f53045f33454daa0ebd95510cf8c860c798d7c2c54f3",
            "runtime-errors-weak","623de9c35e789d8cbaec63bedccce4d9c7eba88fe93fd8859321a2e822d230c4",
            "side-effects-strict","9f189c7271e30b0ebfb78924837254f1da3883460859cf44887cea1908aeed93",
            "side-effects-weak","37e1fa7d6f930f8693025e51fd8bc10989a78b04f386871a4321c7f0df1aafb6",
            "virtual-ref-trait-strict","593bb81c47f0d61c5abb382b1f06513c4bab43d826bcc708a8cac02b12a73c4f",
            "virtual-ref-trait-weak","e99f4a9a4229711d6c1c02ad45b38e433ff44f3f53558a62bf231e728e69605c",
            "wrong-property","fab43365ae2ee6d35c4045a73a068b7449980b9ff4669bb1609d4011ace05e0f");
    private static byte[] source(String id)throws Exception {
        byte[] bytes=PhpNativeTestResources.read(KIT+"/"+id+".php");
        assertEquals(SOURCE_PINS.get(id),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));return bytes;
    }
    private void exact(String family,boolean rejectOutsideBody)throws Throwable {
        for(String mode:new String[]{"weak","strict"})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            String id=family+"-"+mode;byte[] script=source(id);
            try(var fixture=PhpNativeTestResources.fixture(KIT,id+".php",id+".php")) {
                var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();var env=new Environment(scope,out);
                env.getDefaultBuffer().setChunkSize(0);env.defineConstant("STDERR",ObjectMemory.valueOf(new MiscStream(env,err)),true);
                try {
                    var context=new Context(new String(script,StandardCharsets.UTF_8),fixture.file());
                    context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
                    if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);
                        codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                    String label=id+" debug="+debug+" PHB="+phb;
                    assertArrayEquals(label,PhpNativeTestResources.read(KIT+"/"+id+".stdout"),out.toByteArray());
                    assertArrayEquals(label+" stderr",PhpNativeTestResources.read(KIT+"/"+id+".stderr"),err.toByteArray());
                    assertNull(PhpRuntimeOwnership.current(env));
                    if(rejectOutsideBody) {
                        var owner=env.fetchClass("C0",false);assertNotNull(owner);
                        var hook=owner.findProperty("x").getPhpHooks().get();assertNotNull(hook.method().getNativeMethod());
                        var receiver=owner.newObject(env,TraceInfo.UNKNOWN,false,false);
                        boolean rejected=false;
                        try{PhpPropertyHookParentCall.frame(hook,receiver,env,TraceInfo.UNKNOWN,"x",1,0);}
                        catch(IllegalArgumentException expected){rejected=true;}
                        assertTrue("Actual linked hook metadata cannot forge an executing hook-body entry",rejected);
                    }
                }finally{env.doFinal();}
            }
        }
    }
    @Test public void parentGetterSetterArgumentsAndOrdinaryBackingOperationsMatchNativeOrder()throws Throwable {
        exact("side-effects",false);
    }
    @Test public void inheritedVirtualReferenceAndTraitParentBodiesKeepTheirActualDeclaringScope()throws Throwable {
        exact("virtual-ref-trait",false);
    }
    @Test public void parentErrorsAndInvalidLexicalCallsCannotAcquireAnUnrelatedHookBody()throws Throwable {
        exact("runtime-errors",true);
        for(String id:new String[]{"cross-kind","wrong-property","outside-hook","get-acquisition"}) {
            byte[] script=source(id);byte[] captured=PhpNativeTestResources.read(KIT+"/"+id+".stderr");
            assertEquals("Native rejected before any body output",0,PhpNativeTestResources.read(KIT+"/"+id+".stdout").length);
            String first=new String(captured,StandardCharsets.UTF_8).lines().findFirst().orElseThrow();
            assertTrue(first.startsWith("Fatal error: "));String reason=first.substring("Fatal error: ".length(),first.lastIndexOf(" in "));
            for(boolean debug:new boolean[]{false,true}) {
                var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
                var directory=java.nio.file.Files.createTempDirectory("jphp-hook-parent-negative-");var file=directory.resolve(id+".php");
                try {
                    java.nio.file.Files.write(file,script);
                    var context=new Context(new String(script,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    Throwable actual=null;try{new JvmCompiler(env,context).compile(false);}catch(Throwable failure){actual=failure;}
                    assertNotNull(id+" must fail before JVM/body execution",actual);
                    assertTrue(id+" actual="+actual,String.valueOf(actual.getMessage()).contains(reason));
                    assertEquals(0,output.size());
                }finally{try{env.doFinal();}finally{java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.delete(directory);}}
            }
        }
    }
}
