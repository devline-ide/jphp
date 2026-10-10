package org.develnext.jphp.json.gson;

import org.develnext.jphp.json.JsonSerializable;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.reflection.ClassEntity;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class MemorySerializerIdentityTest {
    private interface Work { void run() throws Throwable; }

    // Keep constructor/TLS/handler state off the JUnit runner, including on old runtime JARs.
    private void isolated(Work work) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { work.run(); } catch (Throwable error) { failure.set(error); }
        }, "json-identity-test");
        thread.setDaemon(true);
        thread.start();
        thread.join(10000);
        assertFalse("JSON conversion did not finish", thread.isAlive());
        if (failure.get() != null) throw failure.get();
    }

    private Environment environment() { return new Environment(new CompileScope(), new ByteArrayOutputStream()); }
    private ClassEntity clazz(Environment env) {
        ClassEntity clazz = new ClassEntity(new Context(""));
        clazz.setName("JsonPayload");
        return clazz;
    }
    private String encode(MemorySerializer serializer, Memory value) {
        return serializer.serialize(value, Memory.class, null).toString();
    }
    private MemorySerializer serializer(Environment env) {
        MemorySerializer serializer = new MemorySerializer();
        serializer.setEnv(env);
        return serializer;
    }

    @Test public void repeatedObjectsAndDiamondAreNotRecursion() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                BaseObject leaf = new BaseObject(env, clazz(env)) {};
                leaf.getProperties().put("value", LongMemory.valueOf(1));
                ObjectMemory memory = new ObjectMemory(leaf);
                MemorySerializer serializer = serializer(env);
                assertEquals("[{\"value\":1},{\"value\":1}]", encode(serializer, new ArrayMemory(memory, memory)));
                BaseObject parent = new BaseObject(env, clazz(env)) {};
                parent.getProperties().put("left", memory);
                parent.getProperties().put("right", memory);
                assertEquals("{\"left\":{\"value\":1},\"right\":{\"value\":1}}", encode(serializer, new ObjectMemory(parent)));
            } finally { env.doFinal(); }
        });
    }

    private static final class Payload extends BaseObject implements JsonSerializable {
        int calls;
        Memory result;
        RuntimeException failure;
        Payload(Environment env, ClassEntity clazz) { super(env, clazz); }
        @Override public Memory jsonSerialize(Environment env, Memory... args) {
            assertSame("Use the explicitly configured serializer environment", getEnvironment(), env);
            assertEquals(1, env.getCallStack().getTop());
            calls++;
            if (failure != null) throw failure;
            if (result != null) return result;
            ArrayMemory value = new ArrayMemory(true);
            value.put("value", LongMemory.valueOf(calls));
            return value;
        }
    }

    @Test public void serializableUsesExplicitEnvironmentAndRunsForEachOccurrence() throws Throwable {
        isolated(() -> {
            Environment owner = environment();
            Environment current = environment();
            try {
                Payload payload = new Payload(owner, clazz(owner));
                ObjectMemory memory = new ObjectMemory(payload);
                assertEquals("[{\"value\":1},{\"value\":2}]", encode(serializer(owner), new ArrayMemory(memory, memory)));
                assertEquals(2, payload.calls);
                assertEquals(0, owner.getCallStack().getTop());
                assertEquals(0, current.getCallStack().getTop());
                assertSame(current, Environment.current());
            } finally { current.doFinal(); owner.doFinal(); }
        });
    }

    @Test public void serializableFailurePreservesThrowableAndUnwindsCallStack() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                Payload payload = new Payload(env, clazz(env));
                RuntimeException failure = new IllegalStateException("json callback failed");
                payload.failure = failure;
                MemorySerializer serializer = serializer(env);
                try { encode(serializer, new ObjectMemory(payload)); fail("Exception was swallowed"); }
                catch (RuntimeException actual) { assertSame(failure, actual); }
                assertEquals(0, env.getCallStack().getTop());
                payload.failure = null;
                assertEquals("{\"value\":2}", encode(serializer, new ObjectMemory(payload)));
                assertEquals(0, env.getCallStack().getTop());
            } finally { env.doFinal(); }
        });
    }

    @Test public void genuineCyclesKeepExistingNullPolicyWithoutPoisoningSiblings() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                BaseObject object = new BaseObject(env, clazz(env)) {};
                ObjectMemory memory = new ObjectMemory(object);
                object.getProperties().put("self", memory);
                assertEquals("[{\"self\":null},{\"self\":null}]", encode(serializer(env), new ArrayMemory(memory, memory)));
                Payload payload = new Payload(env, clazz(env));
                ObjectMemory hook = new ObjectMemory(payload);
                payload.result = new ArrayMemory(hook);
                assertEquals("[[null],[null]]", encode(serializer(env), new ArrayMemory(hook, hook)));
                assertEquals(2, payload.calls);
                assertEquals(0, env.getCallStack().getTop());
            } finally { env.doFinal(); }
        });
    }

    @Test public void classHandlersReleaseObjectTraversalMarkers() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                BaseObject object = new BaseObject(env, clazz(env)) {};
                ObjectMemory memory = new ObjectMemory(object);
                MemorySerializer serializer = serializer(env);
                int[] calls = {0};
                serializer.setClassHandler("JsonPayload", (actualEnv, value) -> {
                    assertSame(env, actualEnv);
                    assertSame(object, value.toValue(ObjectMemory.class).value);
                    return LongMemory.valueOf(++calls[0]);
                });
                assertEquals("[1,2]", encode(serializer, new ArrayMemory(memory, memory)));
                assertEquals(2, calls[0]);
            } finally { env.doFinal(); }
        });
    }

    @Test public void classHandlerRebindDoesNotLeaveTheOriginalObjectMarked() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                BaseObject object = new BaseObject(env, clazz(env)) {};
                ObjectMemory memory = new ObjectMemory(object);
                ArrayMemory values = new ArrayMemory(memory, memory);
                values.refOfIndex(0).assignRef(new ReferenceMemory(memory));
                MemorySerializer serializer = serializer(env);
                int[] calls = {0};
                serializer.setClassHandler("JsonPayload", (actualEnv, value) -> {
                    if (value instanceof ReferenceMemory) ((ReferenceMemory) value).assign(Memory.NULL);
                    return LongMemory.valueOf(++calls[0]);
                });
                assertEquals("[1,2]", encode(serializer, values));
                assertEquals(2, calls[0]);
            } finally { env.doFinal(); }
        });
    }
}
