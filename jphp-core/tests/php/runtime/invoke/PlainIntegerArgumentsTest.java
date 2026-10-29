package php.runtime.invoke;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.env.*;
import php.runtime.memory.*;
import php.runtime.reflection.ParameterEntity;

import static org.junit.Assert.*;

public class PlainIntegerArgumentsTest {
    private ParameterEntity parameter() {
        ParameterEntity parameter = new ParameterEntity(null);
        parameter.setName("value");
        return parameter;
    }

    private Memory[] prepare(Environment env, Memory[] args, ParameterEntity parameter, boolean owned) {
        return InvokeArgumentHelper.makeArgumentsForTarget(env, args, new Object(), new ParameterEntity[]{parameter},
                "Target", "run", null, TraceInfo.UNKNOWN, false, owned);
    }

    @Test public void onlyTheOwnedVectorCanBeReused() throws Throwable {
        Environment env = new Environment(new CompileScope());
        try {
            Memory[] input = {LongMemory.valueOf(10001)};
            assertSame(input, prepare(env, input, parameter(), true));
            Memory[] borrowed = prepare(env, input, parameter(), false);
            assertNotSame(input, borrowed);
            assertSame(input[0], borrowed[0]);
        } finally { env.doFinal(); }
    }

    @Test public void typedAndReferenceParametersRetainPreparation() throws Throwable {
        Environment env = new Environment(new CompileScope());
        try {
            ParameterEntity typed = parameter(); typed.setType(HintType.STRING);
            assertTrue(prepare(env, new Memory[]{LongMemory.valueOf(10001)}, typed, true)[0].isString());
            ParameterEntity reference = parameter(); reference.setReference(true);
            ReferenceMemory cell = new ReferenceMemory(LongMemory.valueOf(20003));
            assertSame(cell, prepare(env, new Memory[]{cell}, reference, true)[0]);
        } finally { env.doFinal(); }
    }

    @Test public void memoryAndParameterSubclassHooksRemainObservable() throws Throwable {
        Environment env = new Environment(new CompileScope());
        try {
            int[] reads = {0};
            LongMemory observed = new LongMemory(10001) {
                @Override public Memory toValue() { reads[0]++; return super.toValue(); }
            };
            ParameterEntity readOnly = parameter(); readOnly.setMutable(false);
            prepare(env, new Memory[]{observed}, readOnly, true);
            assertTrue(reads[0] > 0);
            int[] metadata = {0};
            ParameterEntity custom = new ParameterEntity(null) {
                @Override public boolean isReference() { metadata[0]++; return super.isReference(); }
            };
            custom.setName("value");
            prepare(env, new Memory[]{LongMemory.valueOf(20003)}, custom, true);
            assertTrue(metadata[0] > 0);
        } finally { env.doFinal(); }
    }

    @Test public void defaultsAndExtraArgumentConversionsRemainObservable() throws Throwable {
        Environment env = new Environment(new CompileScope());
        try {
            ParameterEntity optional = parameter(); optional.setDefaultValue(LongMemory.valueOf(10001));
            assertEquals(10001, prepare(env, new Memory[0], optional, true)[0].toLong());
            int[] conversions = {0};
            LongMemory extra = new LongMemory(20003) {
                @Override public Memory toImmutable() { conversions[0]++; return super.toImmutable(); }
            };
            Memory[] passed = prepare(env, new Memory[]{LongMemory.valueOf(10001), extra}, parameter(), true);
            assertEquals(2, passed.length);
            assertTrue(conversions[0] > 0);
        } finally { env.doFinal(); }
    }
}
