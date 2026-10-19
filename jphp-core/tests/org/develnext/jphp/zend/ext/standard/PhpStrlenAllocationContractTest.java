package org.develnext.jphp.zend.ext.standard;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.memory.BinaryMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import static org.junit.Assert.*;

/** Java UTF-16 interoperability and subclass dispatch, supplementing unchanged native PHP fixtures. */
public class PhpStrlenAllocationContractTest {
    private Memory call(Memory value) throws Throwable {
        CompileScope scope = new CompileScope(); scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        Environment env = new Environment(scope);
        try { return StringFunctions.strlen(env, TraceInfo.UNKNOWN, value); }
        finally { env.doFinal(); scope.getClassLoader().close(); }
    }

    private long length(Memory value) throws Throwable { return call(value).toLong(); }

    @Test public void unicodeAndMalformedJavaStringsMatchJdkUtf8Encoding() throws Throwable {
        for (String value : new String[] {"", "ascii\0", "\u00e9\u042f\u4e2d\u6587", "\ud83d\ude42\ud834\udd1e", "\ud800", "\udc00", "a\ud800b",
                "\ud800\ud800\udc00", "\udc00\ud800", "\udbff\udfff", "\udbff\udfff\udc00"})
            assertEquals(value.length() + " UTF-16 units", value.getBytes(StandardCharsets.UTF_8).length, length(new StringMemory(value)));
    }

    @Test public void allUtf16CodeUnitsMatchJdkEncoderWithoutScalarAssumptions() throws Throwable {
        char[] units = new char[65536]; for (int i = 0; i < units.length; i++) units[i] = (char) i;
        String value = "\u042f" + new String(units);
        assertEquals(value.getBytes(StandardCharsets.UTF_8).length, length(new StringMemory(value)));
    }

    @Test public void ordinaryBinaryAndReferenceValuesKeepByteLength() throws Throwable {
        byte[] bytes = {(byte) 0xff, 0, (byte) 0xc3, (byte) 0xa9};
        BinaryMemory binary = new BinaryMemory(bytes); assertSame(bytes, binary.getBinaryBytes(StandardCharsets.UTF_8));
        assertEquals(bytes.length, length(binary));
        assertEquals(bytes.length, length(new ReferenceMemory(binary)));
        String value = "\u00e9\ud83d\ude42\ud800";
        assertEquals(value.getBytes(StandardCharsets.UTF_8).length, length(new ReferenceMemory(new StringMemory(value))));
    }

    @Test public void unknownStringSubclassRetainsOriginalByteOverrideAndUnwrapCalls() throws Throwable {
        class Custom extends StringMemory {
            int unwraps, bytes;
            Custom() { super("ordinary text"); }
            @Override public Memory toValue() { unwraps++; return this; }
            @Override public byte[] getBinaryBytes(Charset charset) { bytes++; return new byte[7]; }
            @Override public String toString() { throw new AssertionError("Subclass byte contract was bypassed"); }
        }
        Custom baseline = new Custom(), candidate = new Custom();
        long expected = NativeScalarArguments.string(null, TraceInfo.UNKNOWN, "strlen", 1, "string", baseline).length;
        assertEquals(expected, length(candidate)); assertEquals(baseline.unwraps, candidate.unwraps);
        assertEquals(baseline.bytes, candidate.bytes); assertEquals(7, expected);
    }

    @Test public void unknownBinaryAndReferenceSubclassesRemainOnOriginalDispatch() throws Throwable {
        BinaryMemory binary = new BinaryMemory((byte) 1) {
            @Override public byte[] getBinaryBytes(Charset charset) { return new byte[11]; }
        };
        assertEquals(11, length(binary));
        ReferenceMemory wrapped = new ReferenceMemory(new StringMemory("\u00e9\ud83d\ude42")) {
            @Override public byte[] getBinaryBytes(Charset charset) { throw new AssertionError("Reference byte override is not original unwrap behavior"); }
        };
        assertEquals(NativeScalarArguments.string(null, TraceInfo.UNKNOWN, "strlen", 1, "string", wrapped).length, length(wrapped));
    }

    @Test public void fullStrlenKeepsModernBytesAndLegacyUtf16Length() throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            CompileScope scope = new CompileScope(); scope.setPhpLanguageProfile(profile);
            Environment env = new Environment(scope);
            try {
                assertEquals(profile == PhpLanguageProfile.PHP_8_5 ? 6 : 3,
                        StringFunctions.strlen(env, TraceInfo.UNKNOWN, new StringMemory("\u00e9\ud83d\ude42")).toLong());
            } finally { env.doFinal(); scope.getClassLoader().close(); }
        }
    }

    @Test public void shortThresholdAndFirstCharacterBoundaryKeepJdkBytes() throws Throwable {
        for (String value : new String[] {"a".repeat(8), "a".repeat(9),
                "\u00e9" + "a".repeat(8), "\u00ff" + "a".repeat(8), "\u0100" + "a".repeat(8),
                "\u042f" + "a".repeat(8), "a" + "\u042f".repeat(8),
                "a".repeat(8) + "\ud83d\ude42", "\ud83d\ude42" + "a".repeat(8)})
            assertEquals(value.getBytes(StandardCharsets.UTF_8).length, length(new StringMemory(value)));
    }

    @Test public void sparseAndDenseUnicodeStorageCasesKeepJdkBytes() throws Throwable {
        for (String value : new String[] {"\u4e2d" + "a".repeat(320), "\u042f" + "a".repeat(320),
                "\u00e9".repeat(4096), "\u00e9" + "a".repeat(320), "a" + "\u4e2d".repeat(320),
                "\u042f\u4e2d\u6587\u00e9".repeat(4096)})
            assertEquals(value.getBytes(StandardCharsets.UTF_8).length, length(new StringMemory(value)));
    }

    @Test public void scannedAndFallbackLengthsPreserveOriginalIntOverloadCaching() throws Throwable {
        StringMemory scanned = new StringMemory("\u042f".repeat(2500));
        StringMemory fallback = new StringMemory("a".repeat(5000));
        BinaryMemory binary = new BinaryMemory(new byte[5000]);
        for (Memory value : new Memory[] {scanned, fallback, binary, new ReferenceMemory(scanned)}) {
            Memory first = call(value);
            Memory second = call(value);
            assertEquals(5000, first.toLong()); assertNotSame("Length5000 must retain original int-overload allocation", first, second);
        }
        assertSame(LongMemory.valueOf(3), call(new StringMemory("abc")));
    }
}
