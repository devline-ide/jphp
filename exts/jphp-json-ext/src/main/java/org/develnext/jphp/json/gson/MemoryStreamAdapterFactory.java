package org.develnext.jphp.json.gson;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.develnext.jphp.json.JsonSerializable;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Wire encoding preserves physical duplicate keys; the public JsonElement serializer remains separate. */
public final class MemoryStreamAdapterFactory implements TypeAdapterFactory {
    private final MemorySerializer serializer;

    public MemoryStreamAdapterFactory(MemorySerializer serializer) {
        this.serializer = serializer;
    }

    @Override
    public <T> TypeAdapter<T> create(final Gson gson, final TypeToken<T> type) {
        if (!Memory.class.isAssignableFrom(type.getRawType())) return null;
        return new TypeAdapter<T>() {
            private TypeAdapter<T> readDelegate;

            @Override public T read(JsonReader reader) throws IOException {
                // Writing a virtual/reference subclass must never build a reflective Java-field adapter.
                if (readDelegate == null) readDelegate = gson.getDelegateAdapter(MemoryStreamAdapterFactory.this, type);
                return readDelegate.read(reader);
            }

            @Override public void write(JsonWriter writer, T value) throws IOException {
                if (value == null) writer.nullValue();
                else writeMemory(writer, (Memory) value, new HashSet<Integer>(), true);
            }
        };
    }

    private void writeMemory(JsonWriter writer, Memory value, Set<Integer> active, boolean useHandlers)
            throws IOException {
        if (useHandlers) {
            MemorySerializer.Handler handler = serializer.typeHandlers.get(value.getRealType());
            if (handler != null) {
                writeMemory(writer, handler.call(serializer.getEnv(), value), active, false);
                return;
            }
        }
        switch (value.getRealType()) {
            case BOOL: writer.value(value.toBoolean()); return;
            case DOUBLE: writer.value(value.toDouble()); return;
            case INT: writer.value(value.toLong()); return;
            case STRING:
                Memory numeric = serializer.numericCheck ? StringMemory.toLong(value.toString()) : null;
                if (numeric != null) writer.value(numeric.toLong());
                else writer.value(value.toString());
                return;
            case NULL: writer.nullValue(); return;
            case ARRAY: {
                int pointer = value.getPointer();
                if (!active.add(pointer)) { writer.nullValue(); return; }
                try {
                    ArrayMemory array = value.toValue(ArrayMemory.class);
                    boolean list = serializer.isJsonList(array);
                    if (list) writer.beginArray(); else writer.beginObject();
                    ForeachIterator iterator = array.foreachIterator(false, false);
                    while (iterator.next()) {
                        if (!list) writer.name(iterator.getKey().toString());
                        writeMemory(writer, iterator.getValue(), active, useHandlers);
                    }
                    if (list) writer.endArray(); else writer.endObject();
                } finally { active.remove(pointer); }
                return;
            }
            case OBJECT: {
                int pointer = value.getPointer();
                if (!active.add(pointer)) { writer.nullValue(); return; }
                try {
                    IObject object = value.toValue(ObjectMemory.class).value;
                    if (useHandlers) {
                        for (ClassEntity clazz = object.getReflection(); clazz != null; clazz = clazz.getParent()) {
                            MemorySerializer.Handler handler = serializer.classHandlers.get(clazz.getLowerName());
                            if (handler != null) {
                                writeMemory(writer, handler.call(serializer.getEnv(), value), active, false);
                                return;
                            }
                        }
                    }
                    if (object instanceof JsonSerializable) {
                        Environment env = serializer.getEnv();
                        if (env == null) env = Environment.current();
                        env.pushCall(object, "jsonSerialize");
                        try {
                            writeMemory(writer, ((JsonSerializable) object).jsonSerialize(env), active, useHandlers);
                        } finally { env.popCall(); }
                    } else if(object instanceof php.runtime.lang.BaseEnum actual) {
                        writeMemory(writer,serializer.enumValue(actual),active,useHandlers);
                    } else {
                        writer.beginObject();
                        Environment host=serializer.getEnv();if(host==null)host=Environment.current();
                        ForeachIterator iterator = php.runtime.PhpPropertyHookAccess.consumerProperties(object,host,host==null?php.runtime.env.TraceInfo.UNKNOWN:host.trace(),null,1);
                        while (iterator.next()) {
                            String key = iterator.getKey().toString();
                            Memory property = iterator.getValue();
                            if (!key.startsWith("\0") && !property.isUndefined()) {
                                writer.name(key);
                                writeMemory(writer, property, active, useHandlers);
                            }
                        }
                        writer.endObject();
                    }
                } finally { active.remove(pointer); }
                return;
            }
            default: writer.nullValue();
        }
    }
}
