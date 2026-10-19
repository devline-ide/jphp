package php.runtime;

import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.junit.Test;
import php.runtime.common.Modifier;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.env.handler.ErrorReportHandler;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Tests the compiler-facing union against the same native conversion pins as the byte runtime. */
public class PhpRawByteUnionBuilderTest extends JvmCompilerCase {
    private Environment environment() {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        return new Environment(scope,new ByteArrayOutputStream());
    }
    private static String hex(Object value) {
        return HexFormat.of().formatHex(value instanceof Memory memory ? memory.getBinaryBytes(StandardCharsets.UTF_8)
                : ((String)value).getBytes(StandardCharsets.UTF_8));
    }
    @Test public void ordinaryScalarAppendsKeepOneTextBuilderAndReturnJavaString() throws Throwable {
        var env=environment();var text=new StringBuilder();Object builder=text;
        builder=PhpByteStringBuilders.append(builder,"é",env,TraceInfo.UNKNOWN);
        builder=PhpByteStringBuilders.append(builder,'x',env,TraceInfo.UNKNOWN);
        builder=PhpByteStringBuilders.append(builder,17L,env,TraceInfo.UNKNOWN);
        builder=PhpByteStringBuilders.append(builder,true,env,TraceInfo.UNKNOWN);
        builder=PhpByteStringBuilders.append(builder,false,env,TraceInfo.UNKNOWN);
        builder=PhpByteStringBuilders.append(builder,Memory.NULL,env,TraceInfo.UNKNOWN);
        builder=PhpByteStringBuilders.append(builder,new ReferenceMemory(new StringMemory("z")),env,TraceInfo.UNKNOWN);
        env.setConfigValue("precision",LongMemory.valueOf(3));
        builder=PhpByteStringBuilders.append(builder,DoubleMemory.valueOf(2.675),env,TraceInfo.UNKNOWN);
        assertSame(text,builder);assertEquals(StringBuilder.class,builder.getClass());
        Object result=PhpByteStringBuilders.finishObject(builder);assertEquals(String.class,result.getClass());assertEquals("éx171z2.67",result);
        assertThrows(IllegalArgumentException.class,()->PhpByteStringBuilders.finishObject(new Object()));env.doFinal();
    }
    @Test public void rawPromotionKeepsMixedUtf8BytesAndImmutableResults() throws Throwable {
        var env=environment();var text=new StringBuilder("é");Object builder=text;
        builder=PhpByteStringBuilders.append(builder,PhpByteStringMemory.fromBinaryString("\377"),env,TraceInfo.UNKNOWN);
        assertEquals(PhpByteStringBuilder.class,builder.getClass());
        builder=PhpByteStringBuilders.append(builder,"🙂",env,TraceInfo.UNKNOWN);
        Object first=PhpByteStringBuilders.finishObject(builder);assertEquals(PhpByteStringMemory.class,first.getClass());assertEquals("c3a9fff09f9982",hex(first));
        Object same=PhpByteStringBuilders.append(builder,12L,env,TraceInfo.UNKNOWN);assertSame(builder,same);
        assertEquals("c3a9fff09f99823132",hex(PhpByteStringBuilders.finishObject(same)));assertEquals("c3a9fff09f9982",hex(first));
        assertEquals("é",text.toString());env.doFinal();
    }
    @Test public void warningsOccurOnceAndStringableBytesStayExact() throws Throwable {
        var env=environment();var messages=new ArrayList<SystemMessage>();
        env.setErrorReportHandler(new ErrorReportHandler(){
            public boolean onError(SystemMessage message){messages.add(message);return true;}
            public boolean onFatal(ErrorException failure){throw new AssertionError(failure);}
        });
        Object builder=new StringBuilder();builder=PhpByteStringBuilders.append(builder,Double.NaN,env,TraceInfo.UNKNOWN);
        builder=PhpByteStringBuilders.append(builder,new ArrayMemory(),env,TraceInfo.UNKNOWN);
        assertEquals("NANArray",PhpByteStringBuilders.finishObject(builder));assertEquals(2,messages.size());
        assertEquals("unexpected NAN value was coerced to string",messages.get(0).getMessage());assertEquals("Array to string conversion",messages.get(1).getMessage());
        var events=new ArrayList<String>();var trace=new TraceInfo("union-stringable.php",9,5);int top=env.getCallStackTop();
        var entity=new ClassEntity(new Context(""));entity.setName("UnionByteStringable");entity.setInternal(true);entity.setNativeClazz(PhpRawByteRuntimeTest.StringableProvider.class);
        var method=new MethodEntity(new Context(""));method.setName("__toString");method.setClazz(entity);method.setModifier(Modifier.PUBLIC);method.setParameters(new ParameterEntity[0]);method.setEmpty(false);
        method.setNativeMethod(PhpRawByteRuntimeTest.StringableProvider.class.getMethod("actualStringableBody",Environment.class,Memory[].class));entity.methodMagicToString=method;
        var right=new ReferenceMemory(new StringMemory("before"));
        Memory object=new ObjectMemory(new PhpRawByteRuntimeTest.StringableProvider(env,entity,events,"left",new BinaryMemory((byte)255),trace,()->right.assign("after"),null));
        builder=PhpByteStringBuilders.append(new StringBuilder(),object,env,trace);
        builder=PhpByteStringBuilders.append(builder,right,env,trace);
        assertEquals("ff6166746572",hex(PhpByteStringBuilders.finishObject(builder)));assertEquals(List.of("left"),events);assertEquals(top,env.getCallStackTop());assertEquals(2,messages.size());env.doFinal();
    }
}
