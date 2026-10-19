package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.reflection.ModuleEntity;

public class PhpDeferredClassCodecTest {
    private interface Check { void run(Environment env,Context context,ModuleEntity module,byte[] bytes) throws Throwable; }
    private void fixture(Check check) throws Throwable {
        var scope=new CompileScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env=new Environment(scope);var context=new Context("function define(){if(true){class Repeated{}}else{class Repeated{}}}");
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        try {var module=new JvmCompiler(env,context).compile(false);var output=new ByteArrayOutputStream();
            new ModuleDumper(context,env,true).save(module,output);check.run(env,context,module,output.toByteArray());
        } finally {try{env.doFinal();}finally{scope.getClassLoader().close();}}
    }
    private int catalog(byte[] bytes) {
        byte[] stamp=ByteBuffer.allocate(4).putInt(0x44434c53).array();
        for(int i=0;i<bytes.length-4;i++)if(java.util.Arrays.equals(stamp,java.util.Arrays.copyOfRange(bytes,i,i+4)))return i;
        throw new AssertionError("Missing catalog stamp");
    }
    private void rejected(Context context,Environment env,byte[] bytes) throws Exception {
        try{new ModuleDumper(context,env,true).load(new ByteArrayInputStream(bytes));fail("Malformed catalog accepted");}
        catch(DumpException expected){}
        assertNull(env.fetchClass("Repeated",false,PhpLanguageProfile.PHP_8_5));
    }
    @Test public void ordinalCatalogRoundTripsDuplicateNamesWithoutPublishing() throws Throwable {fixture((env,context,module,bytes)->{
        var copy=new ModuleDumper(context,env,true).load(new ByteArrayInputStream(bytes));
        assertTrue(copy.getClasses().isEmpty());assertEquals(2,copy.getDeferredClasses().size());
        for(int i=0;i<2;i++){var clazz=copy.findDeferredClass(i);assertEquals(i,clazz.getDeclarationId());
            assertSame(copy,clazz.getModule());assertTrue(clazz.isDeclarationTemplate());assertFalse(clazz.isStatic());
            assertNull(clazz.getNativeClass());assertEquals(copy.getInternalName()+"_declaration"+i,clazz.getCompiledInternalName());}
        assertNotSame(copy.findDeferredClass(0),copy.findDeferredClass(1));
        assertNull(env.fetchClass("Repeated",false,PhpLanguageProfile.PHP_8_5));
        assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(bytes)).getCompilerAbiVersion());
    });}
    @Test public void badCatalogStampVersionAndCountsReject() throws Throwable {fixture((env,context,module,bytes)->{
        int offset=catalog(bytes);
        for(int delta:new int[]{0,4,8}){byte[] bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset+delta,-1);rejected(context,env,bad);}
        byte[] bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset+8,1000001);rejected(context,env,bad);
    });}
    @Test public void physicalNameMustMatchExactOrdinal() throws Throwable {fixture((env,context,module,bytes)->{
        byte[] token=(module.getInternalName()+"_declaration0").getBytes(StandardCharsets.UTF_8);byte[] bad=bytes.clone();int changes=0;
        for(int i=0;i<=bad.length-token.length;i++)if(java.util.Arrays.equals(token,java.util.Arrays.copyOfRange(bad,i,i+token.length))){bad[i+token.length-1]='9';changes++;}
        assertTrue(changes>0);rejected(context,env,bad);
    });}
}
