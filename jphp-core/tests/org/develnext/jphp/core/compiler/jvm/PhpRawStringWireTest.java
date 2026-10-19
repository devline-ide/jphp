package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.*;
import php.runtime.loader.dump.io.*;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PhpRawStringWireTest {
    private static final byte[] RAW={(byte)0xff,0,(byte)0xed,(byte)0xa0,(byte)0x80,(byte)0x80,65};
    private byte[] encode(Memory value,boolean capable) throws Exception {
        var bytes=new ByteArrayOutputStream();
        new DumpOutputStream(bytes,capable).writeMemory(value);
        return bytes.toByteArray();
    }
    private void exact(Memory value) {
        assertTrue(value.toValue() instanceof PhpByteStringMemory);
        assertArrayEquals(RAW,value.getBinaryBytes(StandardCharsets.UTF_8));
    }
    private void rejected(byte[] wire,Class<? extends Throwable> type,boolean capable) throws Exception {
        try {new DumpInputStream(new ByteArrayInputStream(wire),capable).readMemory();fail("Accepted malformed raw string");}
        catch(Throwable error){if(!type.isInstance(error))throw error;}
    }
    @Test public void roundTripPreservesInvalidUtf8NulAndReturnedStorageIsDefensive() throws Exception {
        for(byte[] input:new byte[][]{RAW,new byte[0],new byte[]{0},new byte[]{(byte)0xff}}){
            var read=new DumpInputStream(new ByteArrayInputStream(encode(new PhpByteStringMemory(input),true)),true).readMemory();
            assertTrue(read instanceof PhpByteStringMemory);
            assertArrayEquals(input,read.getBinaryBytes(StandardCharsets.UTF_8));
            var copy=read.getBinaryBytes(StandardCharsets.UTF_8);
            if(copy.length>0)copy[0]^=1;
            assertArrayEquals(input,read.getBinaryBytes(StandardCharsets.UTF_8));
        }
    }
    @Test public void ordinaryStringAndArrayWireIsUnchangedWhenCapabilityEnabled() throws Exception {
        for(Memory value:new Memory[]{new StringMemory("\u041f\u0440\u0438\u0432\u0435\u0442\0"),Memory.NULL,LongMemory.valueOf(42),new ArrayMemory(new StringMemory("A"),Memory.TRUE)})
            assertArrayEquals(encode(value,false),encode(value,true));
    }
    @Test public void historicalWriterAndReaderRefuseRawCarrierWithoutPayloadConsumption() throws Exception {
        var out=new ByteArrayOutputStream();
        try{new DumpOutputStream(out).writeMemory(new PhpByteStringMemory(RAW));fail();}catch(DumpException expected){}
        assertEquals(0,out.size());
        byte[] wire=encode(new PhpByteStringMemory(RAW),true);
        var input=new ByteArrayInputStream(wire);
        try{new DumpInputStream(input).readMemory();fail();}catch(DumpException expected){}
        assertEquals(wire.length-4,input.available());
    }
    @Test public void malformedStampVersionLengthAndTruncatedPayloadAreRejected() throws Exception {
        byte[] good=encode(new PhpByteStringMemory(RAW),true);
        for(int offset:new int[]{4,8}){
            byte[] corrupt=good.clone();corrupt[offset]^=1;
            rejected(corrupt,DumpException.class,true);
        }
        for(int length:new int[]{-1,DumpOutputStream.MAX_RAW_STRING_BYTES+1,Integer.MAX_VALUE}){
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(-4);out.writeInt(DumpOutputStream.RAW_STRING_STAMP);out.writeInt(1);out.writeInt(length);
            rejected(bytes.toByteArray(),DumpException.class,true);
        }
        rejected(java.util.Arrays.copyOf(good,good.length-1),EOFException.class,true);
    }
    @Test public void hugeDeclaredButAbsentPayloadIsRejectedWithoutDeclaredAllocation() throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(-4);out.writeInt(DumpOutputStream.RAW_STRING_STAMP);out.writeInt(1);
        out.writeInt(DumpOutputStream.MAX_RAW_STRING_BYTES);
        for(int i=0;i<50;i++)rejected(bytes.toByteArray(),EOFException.class,true);
    }
    @Test public void nestedDumpStreamsInheritOnlyParentCapability() throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DumpOutputStream(new DumpOutputStream(bytes,true));
        out.writeMemory(new PhpByteStringMemory(RAW));
        exact(new DumpInputStream(new DumpInputStream(new ByteArrayInputStream(bytes.toByteArray()),true)).readMemory());
        rejected(bytes.toByteArray(),DumpException.class,false);
    }
    @Test public void moduleWirePreservesConstantsArraysAndFunctionDefaultsWithOriginalProvenance() throws Throwable {
        var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env=new Environment(scope,new ByteArrayOutputStream());
        try{
            var context=new Context("function byteDefault($value='fallback'){return $value;} return 42;");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            var value=new PhpByteStringMemory(RAW);
            var constant=new ConstantEntity("RAW",value,true);constant.setContext(context);constant.setTrace(module.getTrace());
            constant.setModule(module);module.addConstant(constant);
            var array=new ArrayMemory();array.refOfIndex("value").assign(value);
            constant=new ConstantEntity("RAW_ARRAY",array.toImmutable(),true);constant.setContext(context);constant.setTrace(module.getTrace());
            constant.setModule(module);module.addConstant(constant);
            module.getFunctions().iterator().next().getParameters()[0].setDefaultValue(value);
            int original=module.getCompilerProvenance().getCompilerAbiVersion();
            var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
            assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());
            var loaded=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(original,loaded.getCompilerProvenance().getCompilerAbiVersion());
            for(var row:loaded.getConstants()){
                if(row.getName().equals("RAW"))exact(row.getValue(env));
                if(row.getName().equals("RAW_ARRAY"))exact(row.getValue(env).valueOfIndex("value"));
            }
            exact(loaded.getFunctions().iterator().next().getParameters()[0].getDefaultValue());
            scope.loadModule(loaded);scope.addUserModule(loaded);env.registerModule(loaded);
            assertEquals(42,loaded.include(env).toLong());
        }finally{env.doFinal();}
    }
}
