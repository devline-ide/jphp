package org.develnext.jphp.json.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.develnext.jphp.json.JsonSerializable;
import org.develnext.jphp.json.classes.JsonProcessor;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.lang.StdClass;
import php.runtime.memory.*;
import php.runtime.memory.helper.*;
import php.runtime.reflection.ClassEntity;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class MemoryStreamAdapterTest {
    private interface Work { void run() throws Throwable; }
    private void isolated(Work work) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { work.run(); } catch (Throwable error) { failure.set(error); }
        }, "json-wire-test");
        thread.setDaemon(true);
        thread.start();
        thread.join(10000);
        assertFalse("JSON conversion did not finish", thread.isAlive());
        if (failure.get() != null) throw failure.get();
    }
    private Environment environment() { return new Environment(new CompileScope(), new ByteArrayOutputStream()); }
    private ClassEntity clazz() {
        ClassEntity clazz = new ClassEntity(new Context(""));
        clazz.setName("WirePayload");
        return clazz;
    }
    private Gson gson(MemorySerializer serializer, MemoryDeserializer deserializer, boolean nulls, boolean pretty) {
        GsonBuilder builder = new GsonBuilder().disableHtmlEscaping();
        builder.registerTypeAdapter(Memory.class, serializer);
        if (deserializer != null) builder.registerTypeAdapter(Memory.class, deserializer);
        builder.registerTypeAdapterFactory(new MemoryStreamAdapterFactory(serializer));
        if (nulls) builder.serializeNulls();
        if (pretty) builder.setPrettyPrinting();
        return builder.create();
    }
    private ObjectMemory duplicate(Environment env) {
        BaseObject object = new BaseObject(env, clazz()) {};
        object.getProperties().insertBucket("x", LongMemory.valueOf(4));
        object.getProperties().insertBucket("x", LongMemory.valueOf(1));
        object.getProperties().put("nil", Memory.NULL);
        object.getProperties().put("unset", Memory.UNDEFINED);
        object.getProperties().put("\0*\0hidden", LongMemory.valueOf(9));
        return new ObjectMemory(object);
    }

    @Test public void physicalDuplicateWireAndPublicTreeContracts() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                MemorySerializer serializer = new MemorySerializer();
                serializer.setEnv(env);
                ObjectMemory object = duplicate(env);
                Gson gson = gson(serializer, null, true, false);
                assertEquals("{\"x\":4,\"x\":1,\"nil\":null}", gson.toJson(object));
                // JsonElement cannot represent duplicate names; retain its public last-key contract.
                assertEquals("{\"x\":1,\"nil\":null}", serializer.serialize(object, Memory.class, null).toString());
                ArrayMemory map = new ArrayMemory(true);
                map.insertBucket("x", LongMemory.valueOf(4));
                map.insertBucket("x", LongMemory.valueOf(1));
                assertEquals("{\"x\":4,\"x\":1}", gson.toJson(map));
                StringWriter writer = new StringWriter();
                gson.toJson(map, Memory.class, writer);
                assertEquals("{\"x\":4,\"x\":1}", writer.toString());
                assertEquals("[\"<tag>\",1]", gson.toJson(new ArrayMemory("<tag>", 1)));
                serializer.setForceObject(true);
                assertEquals("{\"0\":\"<tag>\",\"1\":1}", gson.toJson(new ArrayMemory("<tag>", 1)));
                serializer.setNumericCheck(true);
                assertEquals("{\"0\":12,\"1\":\"1.2\"}", gson.toJson(new ArrayMemory("12", "1.2")));
            } finally { env.doFinal(); }
        });
    }

    @Test public void processorNullAndPrettyFlagsStayIndependent() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                ObjectMemory object = duplicate(env);
                JsonProcessor ordinary = new JsonProcessor(env, clazz());
                ordinary.__construct(env, Memory.CONST_INT_0);
                assertEquals("{\"x\":4,\"x\":1}", ordinary.format(env, object).toString());
                JsonProcessor configured = new JsonProcessor(env, clazz());
                configured.__construct(env, LongMemory.valueOf(JsonProcessor.SERIALIZE_NULLS | JsonProcessor.SERIALIZE_PRETTY_PRINT));
                assertEquals("{\n  \"x\": 4,\n  \"x\": 1,\n  \"nil\": null\n}", configured.format(env, object).toString());
                assertEquals("{\"x\":4,\"x\":1,\"nil\":null}",
                        org.develnext.jphp.zend.ext.json.JsonFunctions.json_encode(object));
            } finally { env.doFinal(); }
        });
    }

    @Test public void suppliedBuilderKeepsConcreteFormatAndExplicitFormatToContracts() throws Throwable {
        isolated(() -> {
            CompileScope scope = new CompileScope();
            scope.registerExtension(new org.develnext.jphp.json.JsonExtension());
            Environment env = new Environment(scope, new ByteArrayOutputStream());
            try {
                GsonBuilder builder = new GsonBuilder()
                        .registerTypeAdapter(StringMemory.class, (com.google.gson.JsonSerializer<StringMemory>)
                                (value, type, context) -> new com.google.gson.JsonPrimitive("concrete-marker"))
                        .registerTypeAdapter(Memory.class, (com.google.gson.JsonSerializer<Memory>)
                                (value, type, context) -> new com.google.gson.JsonPrimitive("base-marker"));
                JsonProcessor processor = new JsonProcessor(env, builder);
                assertEquals("\"concrete-marker\"", processor.format(env, StringMemory.valueOf("s")).toString());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                var stream = new php.runtime.ext.core.classes.stream.MiscStream(env, (java.io.OutputStream) output);
                processor.formatTo(env, StringMemory.valueOf("s"), new ObjectMemory(stream));
                assertEquals("\"base-marker\"", output.toString(java.nio.charset.StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        });
    }

    @Test public void allMemoryCarriersAndDynamicSubclassesUseSemanticValues() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                JsonProcessor processor = new JsonProcessor(env, clazz());
                processor.__construct(env, LongMemory.valueOf(JsonProcessor.SERIALIZE_NULLS));
                ReferenceMemory cell = new ReferenceMemory(StringMemory.valueOf("s"));
                ArrayMemory array = new ArrayMemory("s");
                Memory[] carriers = {
                    Memory.NULL, Memory.UNDEFINED, Memory.TRUE, Memory.FALSE,
                    LongMemory.valueOf(7), DoubleMemory.valueOf(1.5), StringMemory.valueOf("s"),
                    new BinaryMemory("s"), new StringBuilderMemory("s"), new CharArrayMemory("s"),
                    new BinaryCharArrayMemory("s"), new CharMemory("s", cell, 0), cell,
                    new KeyValueMemory(StringMemory.valueOf("key"), StringMemory.valueOf("s")),
                    new ArrayValueMemory(Memory.CONST_INT_0, array, cell), new ShortcutMemory(cell),
                    new VariadicMemory(StringMemory.valueOf("s")), array, new ObjectMemory(new StdClass(env)),
                    new ReferenceMemory(StringMemory.valueOf("s")) { private final Object opaque = new Object(); }
                };
                String[] expected = {"null", "null", "true", "false", "7", "1.5", "\"s\"", "\"s\"", "\"s\"",
                    "\"s\"", "\"s\"", "\"s\"", "\"s\"", "\"s\"", "\"s\"", "\"s\"", "\"s\"", "[\"s\"]", "{}", "\"s\""};
                assertEquals(expected.length, carriers.length);
                for (int i = 0; i < carriers.length; i++)
                    {
                    assertEquals(carriers[i].getClass().getName(), expected[i], processor.format(env, carriers[i]).toString());
                    assertEquals("Zend " + carriers[i].getClass().getName(), expected[i],
                            org.develnext.jphp.zend.ext.json.JsonFunctions.json_encode(carriers[i]));
                }
            } finally { env.doFinal(); }
        });
    }

    private static final class Payload extends BaseObject implements JsonSerializable {
        int calls;
        Memory result;
        RuntimeException failure;
        Payload(Environment env, ClassEntity clazz) { super(env, clazz); }
        @Override public Memory jsonSerialize(Environment env, Memory... args) {
            assertSame(getEnvironment(), env);
            assertEquals(1, env.getCallStack().getTop());
            calls++;
            if (failure != null) throw failure;
            return result == null ? LongMemory.valueOf(calls) : result;
        }
    }
    @Test public void callbacksEnvironmentRecursionAndFailureUnwindStayIntact() throws Throwable {
        isolated(() -> {
            Environment owner = environment();
            Environment current = environment();
            try {
                MemorySerializer serializer = new MemorySerializer();
                serializer.setEnv(owner);
                Gson gson = gson(serializer, null, true, false);
                Payload payload = new Payload(owner, clazz());
                ObjectMemory value = new ObjectMemory(payload);
                assertEquals("[1,2]", gson.toJson(new ArrayMemory(value, value)));
                payload.result = new ArrayMemory(value);
                assertEquals("[[null],[null]]", gson.toJson(new ArrayMemory(value, value)));
                assertEquals(0, owner.getCallStack().getTop());
                assertSame(current, Environment.current());
                RuntimeException failure = new IllegalStateException("wire callback failure");
                payload.failure = failure;
                try { gson.toJson(value); fail("Exception was swallowed"); }
                catch (RuntimeException actual) { assertSame(failure, actual); }
                assertEquals(0, owner.getCallStack().getTop());
                payload.failure = null;
                serializer.setClassHandler("WirePayload", (env, memory) -> {
                    assertSame(owner, env);
                    return new ArrayMemory(3, 4);
                });
                serializer.setTypeHandler(Memory.Type.INT, (env, memory) -> LongMemory.valueOf(99));
                // Handler output is not recursively rehandled, matching the tree serializer.
                assertEquals("[3,4]", gson.toJson(value));
                ClassEntity child = new ClassEntity(new Context(""));
                child.setName("WireChild");
                child.setParent(payload.getReflection());
                assertEquals("[3,4]", gson.toJson(new ObjectMemory(new BaseObject(owner, child) {})));
                assertEquals("99", gson.toJson(LongMemory.valueOf(1)));
                BaseObject cyclic = new BaseObject(owner, clazz()) {};
                ObjectMemory cycle = new ObjectMemory(cyclic);
                cyclic.getProperties().put("self", cycle);
                serializer.setClassHandler("WirePayload", null);
                assertEquals("[{\"self\":null},{\"self\":null}]", gson.toJson(new ArrayMemory(cycle, cycle)));
            } finally { current.doFinal(); owner.doFinal(); }
        });
    }

    @Test public void streamingWritesPreserveExistingDecodeDelegatesAndFlags() throws Throwable {
        isolated(() -> {
            Environment env = environment();
            try {
                MemorySerializer serializer = new MemorySerializer();
                serializer.setEnv(env);
                MemoryDeserializer deserializer = new MemoryDeserializer();
                deserializer.setEnv(env);
                deserializer.setAssoc(true);
                Gson gson = gson(serializer, deserializer, true, false);
                Memory parsed = gson.fromJson("{\"x\":4,\"x\":1,\"nil\":null}", Memory.class);
                assertTrue(parsed.isArray());
                assertEquals(1, parsed.valueOfIndex("x").toLong());
                assertTrue(parsed.valueOfIndex("nil").isNull());
                assertEquals("{\"x\":1,\"nil\":null}", gson.toJson(parsed));
                deserializer.setMaxDepth(1);
                try { gson.fromJson("[[[1]]]", Memory.class); fail("Depth limit was bypassed"); }
                catch (MemoryDeserializer.MaxDepthException expected) { }
                JsonProcessor processor = new JsonProcessor(env, clazz());
                processor.__construct(env, LongMemory.valueOf(JsonProcessor.DESERIALIZE_AS_ARRAYS | JsonProcessor.DESERIALIZE_LENIENT));
                assertEquals(2, processor.parse(env, StringMemory.valueOf("{value:2}")).valueOfIndex("value").toLong());
            } finally { env.doFinal(); }
        });
    }
}
