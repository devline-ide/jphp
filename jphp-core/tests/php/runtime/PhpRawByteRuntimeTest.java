package php.runtime;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.junit.Test;
import php.runtime.common.Modifier;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.env.handler.ErrorHandler;
import php.runtime.env.handler.ErrorReportHandler;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.lang.BaseObject;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Native byte/string semantics, independent of literal emission and PHB transport. */
public class PhpRawByteRuntimeTest extends JvmCompilerCase {
    private Environment environment() {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        return new Environment(scope,new ByteArrayOutputStream());
    }
    private static PhpByteStringMemory raw(String hex) { return new PhpByteStringMemory(HexFormat.of().parseHex(hex)); }
    private static String hex(Memory value) { return HexFormat.of().formatHex(value.getBinaryBytes(StandardCharsets.UTF_8)); }
    private List<String[]> fixtures(String kind) throws Exception {
        var rows=new ArrayList<String[]>();
        try(var stream=getClass().getClassLoader().getResourceAsStream("resources/strings/php85_raw_byte_runtime.tsv")) {
            assertNotNull(stream);
            for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                String[] fields=line.split("\t",-1);if(fields[0].equals(kind))rows.add(fields);
            }
        }
        return rows;
    }
    private static double number(String value) { return value.equals("INF")?Double.POSITIVE_INFINITY:Double.parseDouble(value); }
    private static List<SystemMessage> reports(Environment env) {
        List<SystemMessage> result=new ArrayList<>();
        env.setErrorReportHandler(new ErrorReportHandler() {
            public boolean onError(SystemMessage message){result.add(message);return true;}
            public boolean onFatal(ErrorException failure){throw new AssertionError(failure);}
        });return result;
    }
    @Test public void nativeBooleanAndNumericByteConversions() throws Exception {
        int count=0;
        for(String[] row:fixtures("scalar")) {
            var value=raw(row[1]);assertEquals(row[1],Boolean.parseBoolean(row[2]),value.toBoolean());
            assertEquals(row[1],Long.parseLong(row[3]),value.toLong());
            assertEquals(row[1],number(row[4]),value.toDouble(),0);
            assertEquals(row[1],Boolean.parseBoolean(row[5]),PhpNumericString.isNumeric(value.toBinaryString()));count++;
        }
        assertEquals(13,count);
    }
    @Test public void nativeStringComparisonsAndIdentityUseBytesInBothDirections() throws Exception {
        int count=0;
        for(String[] row:fixtures("compare")) {
            Memory left=raw(row[1]),right=raw(row[2]);boolean equal=Boolean.parseBoolean(row[3]),identical=Boolean.parseBoolean(row[4]);
            long ordering=Long.parseLong(row[5]);
            assertEquals(row[1]+":"+row[2],equal,PhpComparison.equal(left,right,null,TraceInfo.UNKNOWN));
            assertEquals(identical,left.identical(right));assertEquals(identical,right.identical(left));
            assertEquals(ordering,PhpComparison.spaceshipCompare(left,right,null,TraceInfo.UNKNOWN));
            assertEquals(equal,left.equal(right));assertEquals(ordering<0,left.smaller(right));assertEquals(ordering<=0,left.smallerEq(right));
            byte[] bytes=right.getBinaryBytes(StandardCharsets.UTF_8);String text=new String(bytes,StandardCharsets.UTF_8);
            if(Arrays.equals(bytes,text.getBytes(StandardCharsets.UTF_8))) {
                Memory ordinary=StringMemory.valueOf(text),reference=new ReferenceMemory(left);
                assertEquals(equal,ordinary.equal(left));assertEquals(equal,left.equal(ordinary));
                assertEquals(identical,ordinary.identical(left));assertEquals(identical,left.identical(ordinary));
                assertEquals(equal,ordinary.equal(new ReferenceMemory(left)));
                assertEquals(identical,new ReferenceMemory(ordinary).identical(new ReferenceMemory(left)));
                assertEquals(ordering>=0,ordinary.smallerEq(reference));
            }
            count++;
        }
        assertEquals(9,count);
        assertFalse(new StringMemory("01").equal(new StringMemory("1"))); // historical ordinary path
    }
    @Test public void nativeNumberVersusNonnumericBytesUsesPhp85Ordering() throws Exception {
        for(String[] row:fixtures("number-compare")) {
            Memory value=raw(row[1]);
            assertEquals(Boolean.parseBoolean(row[2]),PhpComparison.equal(Memory.CONST_INT_0,value,null,TraceInfo.UNKNOWN));
            assertEquals(Long.parseLong(row[3]),PhpComparison.spaceshipCompare(Memory.CONST_INT_0,value,null,TraceInfo.UNKNOWN));
            assertEquals(Boolean.parseBoolean(row[4]),PhpComparison.equal(DoubleMemory.valueOf(12.5),value,null,TraceInfo.UNKNOWN));
            assertEquals(Long.parseLong(row[5]),PhpComparison.spaceshipCompare(DoubleMemory.valueOf(12.5),value,null,TraceInfo.UNKNOWN));
        }
    }
    @Test public void immutableBackingDoesNotLeakAndMalformedBytesRemainDistinct() {
        byte[] input=HexFormat.of().parseHex("c3a9fff09f9982");var value=new PhpByteStringMemory(input);input[2]=0;
        assertEquals(7,value.byteLength());assertEquals("c3a9fff09f9982",hex(value));assertSame(value,value.toImmutable());assertSame(value,value.toBinary());
        byte[] exposed=value.getBinaryBytes(StandardCharsets.UTF_8);Arrays.fill(exposed,(byte)0);
        byte[] target=new byte[9];value.copyTo(target,1);target[3]=0;
        assertEquals("c3a9fff09f9982",hex(value));assertEquals("c3a9fff09f9982",HexFormat.of().formatHex(value.getBinaryBytes(StandardCharsets.US_ASCII)));
        Memory malformed=raw("ff"),replacement=new StringMemory("�"),utf8=raw("c3a9"),ordinary=new StringMemory("é");
        assertEquals(malformed.toString(),replacement.toString());assertFalse(malformed.identical(replacement));assertFalse(replacement.identical(malformed));
        assertTrue(utf8.identical(ordinary));assertTrue(ordinary.identical(utf8));
        assertEquals("ff",hex(value.valueOfIndex(TraceInfo.UNKNOWN,2)));assertEquals("82",hex(value.valueOfIndex(TraceInfo.UNKNOWN,-1)));
        assertFalse(raw("ff").equals(raw("efbfbd")));assertEquals(raw("ff").hashCode(),raw("ff").hashCode());
        assertThrows(IndexOutOfBoundsException.class,()->value.copyTo(new byte[6],0));
        assertEquals("00ffc3a9",hex(PhpByteStringMemory.fromBinaryString("\0\377\303\251")));
        assertThrows(IllegalArgumentException.class,()->PhpByteStringMemory.fromBinaryString("Ж"));
    }
    @Test public void nativeByteOffsetsAndDiagnosticsApplyToOrdinaryUtf8Strings() throws Throwable {
        var env=environment();var messages=reports(env);var trace=new TraceInfo("byte-offset-test.php",7,4);
        Memory ordinary=new StringMemory("é�🙂"),value=raw("c3a9fff09f9982");
        for(String[] row:fixtures("offset"))assertEquals(row[2],hex(PhpByteStrings.offset(value,LongMemory.valueOf(Long.parseLong(row[1])),env,trace)));
        assertEquals(3,messages.size());assertEquals("Uninitialized string offset -8",messages.get(0).getMessage());
        assertEquals("Uninitialized string offset 7",messages.get(1).getMessage());assertEquals("Uninitialized string offset 4294967296",messages.get(2).getMessage());
        for(var message:messages){assertEquals(ErrorType.E_WARNING,message.getType());assertSame(trace,message.getTrace().trace);}
        assertEquals("c3",hex(PhpByteStrings.offset(ordinary,Memory.CONST_INT_0,env,trace)));
        messages.clear();Memory ascii=new StringMemory("abcdef");
        for(String spelling:List.of("2"," 2","+2","02"))assertEquals("63",hex(PhpByteStrings.offset(ascii,new StringMemory(spelling),env,trace)));
        assertEquals("66",hex(PhpByteStrings.offset(ascii,new StringMemory("-1"),env,trace)));assertTrue(messages.isEmpty());
        assertEquals("63",hex(PhpByteStrings.offset(ascii,new StringMemory("2x"),env,trace)));
        assertEquals("Illegal string offset \"2x\"",messages.getFirst().getMessage());
        for(Memory index:List.of(DoubleMemory.valueOf(2.7),DoubleMemory.valueOf(2),Memory.TRUE,Memory.FALSE,Memory.NULL)) {
            messages.clear();PhpByteStrings.offset(ascii,index,env,trace);assertEquals(1,messages.size());assertEquals("String offset cast occurred",messages.getFirst().getMessage());
        }
        for(String index:List.of("2.0","x")) {
            var failure=assertThrows(BaseTypeError.class,()->PhpByteStrings.offset(ascii,new StringMemory(index),env,trace));
            assertEquals("Cannot access offset of type string on string",failure.getMessage(env).toString());
        }
        var failure=assertThrows(BaseTypeError.class,()->PhpByteStrings.offset(ascii,new ArrayMemory(),env,trace));
        assertEquals("Cannot access offset of type array on string",failure.getMessage(env).toString());
        env.doFinal();
    }
    @Test public void offsetWarningMutationKeepsTheAlreadySelectedString() throws Throwable {
        for(Memory index:List.of(Memory.TRUE,new StringMemory("1x"),LongMemory.valueOf(3))) {
            var env=environment();var address=new ReferenceMemory(new StringMemory("abc"));
            env.setErrorHandler(new ErrorHandler(null,Memory.NULL,ErrorType.E_WARNING.value) {
                @Override public boolean onError(Environment current,SystemMessage message){assertSame(env,current);address.assign(index.toLong()==3?"XYZW":"XYZ");return true;}
            });
            assertEquals(index.toLong()==3?"":"62",hex(PhpByteStrings.offset(address,index,env,TraceInfo.UNKNOWN)));
            assertEquals(index.toLong()==3?"XYZW":"XYZ",address.toString());env.doFinal();
        }
    }
    public static final class StringableProvider extends BaseObject {
        private final List<String> events;
        private final String label;
        private final Memory returned;
        private final TraceInfo expectedTrace;
        private final Runnable effect;
        private final RuntimeException failure;
        StringableProvider(Environment env,ClassEntity entity,List<String> events,String label,Memory returned,TraceInfo trace,Runnable effect,RuntimeException failure) {
            super(env,entity);this.events=events;this.label=label;this.returned=returned;expectedTrace=trace;this.effect=effect;this.failure=failure;
        }
        public Memory actualStringableBody(Environment env,Memory... arguments) {
            assertSame(getEnvironment(),env);assertSame(expectedTrace,env.peekCall(0).trace);events.add(label);
            if(effect!=null)effect.run();if(failure!=null)throw failure;return returned;
        }
    }
    private ObjectMemory provider(Environment env,List<String> events,String label,Memory result,TraceInfo trace,Runnable effect,RuntimeException failure) throws Exception {
        var entity=new ClassEntity(new Context(""));entity.setName("ByteStringable"+label);entity.setInternal(true);entity.setNativeClazz(StringableProvider.class);
        var method=new MethodEntity(new Context(""));method.setName("__toString");method.setClazz(entity);method.setModifier(Modifier.PUBLIC);
        method.setParameters(new ParameterEntity[0]);method.setEmpty(false);
        method.setNativeMethod(StringableProvider.class.getMethod("actualStringableBody",Environment.class,Memory[].class));entity.methodMagicToString=method;env.registerClass(entity);
        return new ObjectMemory(new StringableProvider(env,entity,events,label,result,trace,effect,failure));
    }
    @Test public void stringableResultsKeepBytesCallCountsTraceAndRightReferenceOrder() throws Throwable {
        var env=environment();var trace=new TraceInfo("byte-concat-test.php",11,3);var events=new ArrayList<String>();int top=env.getCallStackTop();
        var right=new ReferenceMemory(new StringMemory("before"));
        var left=provider(env,events,"left",new BinaryMemory((byte)255),trace,()->right.assign("after"),null);
        assertEquals("ff6166746572",hex(PhpByteStrings.concat(left,right,env,trace)));assertEquals(List.of("left"),events);assertEquals(top,env.getCallStackTop());
        events.clear();var other=provider(env,events,"right",new StringMemory("é"),trace,null,null);
        assertEquals("ffc3a9",hex(PhpByteStrings.concat(left,other,env,trace)));assertEquals(List.of("left","right"),events);assertEquals(top,env.getCallStackTop());
        events.clear();assertFalse(PhpComparison.equal(left,new StringMemory("�"),env,trace));assertEquals(List.of("left"),events);
        events.clear();assertTrue(PhpComparison.equal(left,raw("ff"),env,trace));assertEquals(List.of("left"),events);env.doFinal();
    }
    @Test public void primaryStringableFailureStopsRightConversionAndUnwindsTrace() throws Throwable {
        var env=environment();var events=new ArrayList<String>();var trace=new TraceInfo("byte-concat-failure.php",5,9);int top=env.getCallStackTop();
        var primary=new BaseError(env);primary.__construct(env,new StringMemory("primary-left"));
        var left=provider(env,events,"left",Memory.CONST_EMPTY_STRING,trace,null,primary);
        var right=provider(env,events,"right",raw("ff"),trace,null,null);
        var observed=assertThrows(RuntimeException.class,()->PhpByteStrings.concat(left,right,env,trace));
        assertSame(primary,observed);assertEquals(List.of("left"),events);assertEquals(top,env.getCallStackTop());
        events.clear();assertEquals("ff",hex(PhpByteStrings.concat(Memory.CONST_EMPTY_STRING,right,env,trace)));assertEquals(List.of("right"),events);env.doFinal();
    }
    private ObjectMemory compiledStringable(Environment env,String source) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);
        return new ObjectMemory(env.fetchClass("B").newObjectWithoutConstruct(env));
    }
    @Test public void compiledStringableWeakStrictAndNanReturnConversionsOccurOnce() throws Throwable {
        var weak=environment();var object=compiledStringable(weak,"class B{function __toString(){return 17;}}");
        assertEquals("313778",hex(PhpByteStrings.concat(object,new StringMemory("x"),weak,TraceInfo.UNKNOWN)));weak.doFinal();
        var strict=environment();object=compiledStringable(strict,"declare(strict_types=1);class B{function __toString(){return 17;}}");var strictObject=object;
        var failure=assertThrows(BaseTypeError.class,()->PhpByteStrings.concat(strictObject,new StringMemory("x"),strict,TraceInfo.UNKNOWN));
        assertEquals("B::__toString(): Return value must be of type string, int returned",failure.getMessage(strict).toString());strict.doFinal();
        var nan=environment();var messages=reports(nan);object=compiledStringable(nan,"class B{function __toString(){return NAN;}}");
        assertEquals("4e414e78",hex(PhpByteStrings.concat(object,new StringMemory("x"),nan,TraceInfo.UNKNOWN)));
        assertEquals(1,messages.size());assertEquals("unexpected NAN value was coerced to string",messages.getFirst().getMessage());nan.doFinal();
    }
    @Test public void nativeFloatPrecisionAndBinaryRoundingRemainExact() throws Throwable {
        var env=environment();env.setConfigValue("precision",LongMemory.valueOf(3));
        double[] values={2.675,1.005,9.995,9.999,0.0001,0.00009999,0.0,-0.0};String[] expected={"2.67","1","9.99","10","0.0001","0.0001","0","-0"};
        for(int i=0;i<values.length;i++)assertEquals(expected[i],PhpByteStrings.stringValue(DoubleMemory.valueOf(values[i]),env,TraceInfo.UNKNOWN).toString());
        env.setConfigValue("precision",LongMemory.valueOf(-1));
        double[] shortest={1,1e7,1e15,1e16,1e17,1e20,1e23,1e-4,1e-5,0.84551240822557006};
        String[] texts={"1","10000000","1000000000000000","10000000000000000","1.0E+17","1.0E+20","1.0E+23","0.0001","1.0E-5","0.8455124082255701"};
        for(int i=0;i<shortest.length;i++)assertEquals(texts[i],PhpByteStrings.stringValue(DoubleMemory.valueOf(shortest[i]),env,TraceInfo.UNKNOWN).toString());env.doFinal();
    }
    @Test public void lazyConcatKeepsImmutableSnapshotsAndWarnsOncePerActualConversion() throws Throwable {
        var env=environment();var messages=reports(env);var builder=new PhpByteStringBuilder().append("a").append("é");
        Memory first=builder.toImmutable();assertEquals(StringMemory.class,first.getClass());assertEquals("61c3a9",hex(first));
        builder.append(raw("ff"),env,TraceInfo.UNKNOWN);Memory second=builder.toImmutable();builder.append("z");
        assertEquals("61c3a9",hex(first));assertEquals("61c3a9ff",hex(second));assertEquals("61c3a9ff7a",hex(builder.toImmutable()));
        Memory arrays=PhpByteStrings.concat(new ArrayMemory(),raw("ff"),env,TraceInfo.UNKNOWN);
        arrays=PhpByteStrings.concat(arrays,new ArrayMemory(),env,TraceInfo.UNKNOWN);assertEquals("4172726179ff4172726179",hex(arrays));
        assertEquals(2,messages.size());for(var message:messages)assertEquals("Array to string conversion",message.getMessage());messages.clear();
        assertEquals("4e414e",hex(PhpByteStrings.concat(Memory.CONST_EMPTY_STRING,DoubleMemory.valueOf(Double.NaN),env,TraceInfo.UNKNOWN)));
        assertEquals(1,messages.size());assertEquals("unexpected NAN value was coerced to string",messages.getFirst().getMessage());env.doFinal();
    }
    @Test public void exactScalarAdmissionPreservesTheImmutableCarrier() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("raw-scalar-test");var value=raw("ff");
        try(var execution=participant.enter()) {
            var binding=PhpOwnershipValues.bindValue(domain,value,null);
            assertSame(value,binding.rawValue());assertNull(binding.node());assertEquals(PhpOwnershipValues.Role.VALUE,binding.role());assertEquals(0,domain.graph().liveNodes());
        }finally{participant.close();}
        assertEquals(String.class,Memory.Type.STRING.toClass());assertEquals("éx",new StringMemory("é").concat("x"));
    }
}
