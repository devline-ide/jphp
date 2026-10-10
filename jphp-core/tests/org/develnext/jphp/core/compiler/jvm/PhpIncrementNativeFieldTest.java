package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.PhpArithmetic;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.CompilePropertyEntity;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

public class PhpIncrementNativeFieldTest extends JvmCompilerCase {
    public static class NativeCounter extends BaseObject {
        public long number = 10;
        public NativeCounter(Environment env, ClassEntity clazz) { super(env, clazz); }
    }

    @Test public void testModernOperatorsCommitToNativeJavaField() throws Throwable {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Environment env = new Environment(newScope(), output);
        env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        try {
            Context context = new Context("");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ClassEntity clazz = new ClassEntity(context);
            clazz.setName("NativeCounter");
            CompilePropertyEntity property = new CompilePropertyEntity(context, NativeCounter.class.getField("number"));
            property.setName("number");
            property.setClazz(clazz);
            clazz.addProperty(property);
            NativeCounter counter = new NativeCounter(env, clazz);
            TraceInfo trace = TraceInfo.valueOf(context.getFileName(), 0, 0);
            for (boolean decrement : new boolean[] {false, true}) for (boolean post : new boolean[] {false, true}) {
                counter.number = 10;
                long updated = decrement ? 9 : 11;
                var result = PhpArithmetic.incrementProperty(new ObjectMemory(counter), "number", env, trace,
                        null, 0, decrement, post);
                assertEquals(updated, counter.number);
                assertEquals(post ? 10 : updated, result.toLong());
                assertEquals(0, counter.getProperties().size());
            }
            assertEquals("", output.toString());
        } finally { env.doFinal(); }
    }
}
