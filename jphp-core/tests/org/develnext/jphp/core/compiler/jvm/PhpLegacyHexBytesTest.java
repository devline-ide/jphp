package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.StringMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.junit.Assert.*;

public class PhpLegacyHexBytesTest {
    private interface ResultCheck { void accept(Memory result, byte[] output) throws Throwable; }
    private void modes(String source, PhpLanguageProfile profile, ResultCheck check) throws Throwable {
        for(boolean debug:new boolean[]{false,true}) for(boolean phb:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
            scope.setPhpLanguageProfile(profile);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context(source);context.setPhpLanguageProfile(profile);
                var module=new JvmCompiler(env,context).compile(false);
                if(phb) {var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                Memory result;
                try {result=module.include(env);env.doFinal();}
                catch(Throwable failure) {throw new AssertionError(profile+",debug="+debug+",phb="+phb+": "+failure.getMessage(),failure);}
                try {check.accept(result,output.toByteArray());}
                catch(AssertionError failure) {throw new AssertionError(profile+",debug="+debug+",phb="+phb+": "+failure.getMessage(),failure);}
            } finally {scope.getClassLoader().close();}
        }
    }
    private byte[] resource(String name) throws IOException {
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/legacy_hex/"+name)) {
            if(input==null) throw new FileNotFoundException(name);
            return input.readAllBytes();
        }
    }
    private void nativeCase(String name,PhpLanguageProfile profile) throws Throwable {
        String source=new String(resource(name+".php"),StandardCharsets.UTF_8);byte[] expected=resource(name+".expected");
        modes(source,profile,(result,output)->assertArrayEquals(expected,result.getBinaryBytes(StandardCharsets.UTF_8)));
    }
    @Test public void producerReturnsPhysicalBytesAcrossAllLegacyCallableRoutes() throws Throwable {
        for(var profile:PhpLanguageProfile.values()) for(String hex:new String[]{"","00","30","3041","fffe80","c3a9","00017f80ffc328"}) {
            for(String call:new String[]{"hex2bin('"+hex+"')","$f='hex2bin';return $f('"+hex+"')",
                    "call_user_func_array('hex2bin',array('"+hex+"'))","(new ReflectionFunction('hex2bin'))->invokeArgs(array('"+hex+"'))"}) {
                String source=call.startsWith("$f=") ? call+";" : "return "+call+";";
                modes(source,profile,(result,output)->assertArrayEquals(HexFormat.of().parseHex(hex),result.getBinaryBytes(StandardCharsets.UTF_8)));
            }
        }
    }
    @Test public void ordOffsetsLengthBooleanAndHexRemainByteExact() throws Throwable {
        for(var profile:PhpLanguageProfile.values()) nativeCase("scalar_consumers",profile);
    }
    @Test public void concatAndAssignmentPreserveBytesAndReferences() throws Throwable {
        for(var profile:PhpLanguageProfile.values()) nativeCase("concat_consumers",profile);
    }
    @Test public void rawConcatWithOrdinaryUtf8TextKeepsBothByteSequences() throws Throwable {
        for(var profile:PhpLanguageProfile.values()) nativeCase("concat_unicode",profile);
    }
    @Test public void legacyCharacterWireAndRawParserInputRetainRoundTrips() throws Throwable {
        for(var profile:PhpLanguageProfile.values()) nativeCase("serialization_consumers",profile);
    }
    @Test public void customSerializablePayloadRetainsItsRawCharacterWire() throws Throwable {
        for(var profile:PhpLanguageProfile.values()) nativeCase("custom_serialization",profile);
    }
    @Test public void serializablePrivateDeclarationScopeAndThrowingCleanupMatchNative() throws Throwable {
        for(var profile:PhpLanguageProfile.values()) nativeCase("custom_scope_cleanup",profile);
    }
    @Test public void customSerializableDefaultsExistWithoutInvokingConstructor() throws Throwable {
        for(var profile:PhpLanguageProfile.values()) nativeCase("custom_defaults",profile);
    }
    @Test public void echoEmitsRawBytesAcrossCallableRoutes() throws Throwable {
        String source=new String(resource("echo_consumers.php"),StandardCharsets.UTF_8);byte[] expected=resource("echo_consumers.expected");
        for(var profile:PhpLanguageProfile.values()) modes(source,profile,(result,output)->{
            assertEquals("ok",result.toString());assertArrayEquals(expected,output);
        });
    }
    @Test public void modernOddAndInvalidInputStillWarnsAndReturnsFalse() throws Throwable {
        nativeCase("modern_errors",PhpLanguageProfile.PHP_8_5);
    }
    @Test public void legacyOddAndInvalidInputRetainsHistoricalKernelSemantics() throws Throwable {
        modes("$out='';foreach(array('A','ABC','G1','0g') as $hex){$out.=bin2hex(hex2bin($hex)).';';}return $out;",
                PhpLanguageProfile.JPHP_LEGACY,(result,output)->assertEquals(";ab;01;00;",result.toString()));
        // Existing physical Java caller descriptor stays String; its odd/invalid behavior is deliberately unchanged.
        var method=org.develnext.jphp.zend.ext.standard.StringFunctions.class.getMethod("hex2bin",Memory.class);
        assertSame(String.class,method.getReturnType());
        assertEquals("ab",HexFormat.of().formatHex(((String)method.invoke(null,StringMemory.valueOf("ABC"))).getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("01",HexFormat.of().formatHex(((String)method.invoke(null,StringMemory.valueOf("G1"))).getBytes(StandardCharsets.ISO_8859_1)));
    }
    @Test public void ordinaryLegacyStringsRetainHistoricalConcatAndCharacterWire() throws Throwable {
        modes("$x='A';$r=&$x;$r.=17;return $x.':'.('B'.true).':'.('C'.null).':'.('D' . 1.5).':'.serialize('Ж');",
                PhpLanguageProfile.JPHP_LEGACY,(result,output)->assertEquals("A17:B1:C:D1.5:s:1:\"Ж\";",result.toString()));
    }
    @Test public void legacySerializedTextHostProjectionGapRemainsExplicit() throws Throwable {
        modes("return serialize(hex2bin('c3a9'));",PhpLanguageProfile.JPHP_LEGACY,(result,output)->{
            assertEquals("s:2:\"Ã©\";",result.toString());
            // The old public String serializer still exports UTF8 Java text, not a native byte wire.
            assertEquals("733a323a22c383c2a9223b",HexFormat.of().formatHex(result.getBinaryBytes(StandardCharsets.UTF_8)));
        });
    }
}
