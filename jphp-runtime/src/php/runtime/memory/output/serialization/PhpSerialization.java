package php.runtime.memory.output.serialization;

import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.core.StringFunctions;
import php.runtime.lang.*;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.lang.exception.BaseValueError;
import php.runtime.lang.spl.Serializable;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.PropertyEntity;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** PHP byte-stream serialization. Each invocation owns its graph and callback state. */
public final class PhpSerialization {
    private static final Pattern SERIALIZED_NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][+-]?[0-9]+)?");
    private PhpSerialization() {}

    public static Memory serialize(Environment env, TraceInfo trace, Memory value) throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.JPHP_LEGACY) {
            return StringMemory.valueOf(StringFunctions.serialize(env, trace, value));
        }
        Writer writer = new Writer(env, trace);
        writer.value(value.toValue());
        while (!writer.actions.isEmpty()) writer.actions.pop().run();
        return new BinaryMemory(writer.output.toByteArray());
    }

    public static Memory unserialize(Environment env, TraceInfo trace, Memory value) throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.JPHP_LEGACY) {
            return StringFunctions.unserialize(env, trace, value.toString());
        }
        return unserialize(env, trace, value, new ArrayMemory());
    }

    public static Memory unserialize(Environment env, TraceInfo trace, Memory value, Memory options) throws Throwable {
        byte[] bytes = inputBytes(env, trace, value.toValue());
        if (!options.isArray()) {
            env.exception(trace, BaseTypeError.class, "unserialize(): Argument #2 ($options) must be of type array");
        }
        var context = php.runtime.PhpPropertyWriteContext.enter(env, trace, true);
        try { return new Reader(env, trace, bytes, options.toValue(ArrayMemory.class)).read(); }
        finally { php.runtime.PhpPropertyWriteContext.leave(context); }
    }

    /** Keep the legacy character-count wire while honoring a modern class's storage contract. */
    static boolean restoreLegacyObjectProperties(Environment env, TraceInfo trace, IObject object, ArrayMemory payload) throws Throwable {
        ClassEntity clazz = object.getReflection();
        if (clazz.getModule() == null || clazz.getModule().getPhpLanguageProfile() != PhpLanguageProfile.PHP_8_5) return false;
        var context = php.runtime.PhpPropertyWriteContext.enter(env, trace, true);
        try {
            MethodEntity modern = clazz.findMethod("__unserialize");
            if (modern != null) {
                invokeRestorationHook(env, trace, object, modern, payload);
            } else {
                ArrayMemory properties = object.getProperties();
                Frame keys = new Frame(properties, 0, null, true, clazz);
                ForeachIterator iterator = payload.getNewIterator(env);
                while (iterator.next()) {
                    String storageKey = keys.propertyKey(iterator.getKey().toString());
                    clazz.checkDynamicPropertyRestoration(env, trace, properties, storageKey);
                    properties.getByScalarOrCreate(storageKey, Memory.UNDEFINED).assign(iterator.getValue());
                }
                if (clazz.methodMagicWakeup != null) invokeRestorationHook(env, trace, object, clazz.methodMagicWakeup);
            }
            return true;
        } finally { php.runtime.PhpPropertyWriteContext.leave(context); }
    }

    private static void invokeRestorationHook(Environment env, TraceInfo trace, IObject object, MethodEntity method, Memory... args) throws Throwable {
        env.pushCall(trace, object, method.getName(), args);
        try { method.invokeDynamic(object, env, trace, args); }
        finally { env.popCall(); }
    }

    private static byte[] inputBytes(Environment env, TraceInfo trace, Memory value) throws Throwable {
        if (value.isString()) return value.getBinaryBytes(StandardCharsets.UTF_8);
        php.runtime.reflection.ModuleEntity module = env.getModuleManager().findModule(trace);
        if (module == null || !module.isStrictTypes()) {
            if (value.isNull()) {
                env.error(trace, ErrorType.E_DEPRECATED, "unserialize(): Passing null to parameter #1 ($data) of type string is deprecated");
                return new byte[0];
            }
            if (value.isNumber() || value.type == Memory.Type.BOOL) return value.getBinaryBytes(StandardCharsets.UTF_8);
            if (value.isObject() && !value.isResource()) {
                ObjectMemory object = value.toValue(ObjectMemory.class);
                MethodEntity method = object.getReflection().methodMagicToString;
                if (method != null) {
                    Memory[] args = new Memory[0];
                    env.pushCall(trace, object.value, method.getName(), args);
                    Memory converted;
                    try { converted = method.invokeDynamic(object.value, env, trace, args); }
                    finally { env.popCall(); }
                    if (!converted.isString()) env.exception(trace, BaseTypeError.class, "%s::__toString() must return a string", object.getReflection().getName());
                    return converted.getBinaryBytes(StandardCharsets.UTF_8);
                }
            }
        }
        env.exception(trace, BaseTypeError.class, "unserialize(): Argument #1 ($data) must be of type string");
        return new byte[0];
    }

    private static final class Writer {
        final Environment env;
        final TraceInfo trace;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final IdentityHashMap<Object, Integer> identities = new IdentityHashMap<>();
        final IdentityHashMap<ArrayMemory, Boolean> activeArrays = new IdentityHashMap<>();
        final Deque<Action> actions = new ArrayDeque<>();
        int index;

        Writer(Environment env, TraceInfo trace) { this.env = env; this.trace = trace; }
        void text(String text) { output.writeBytes(text.getBytes(StandardCharsets.UTF_8)); }
        void string(byte[] bytes) { text("s:" + bytes.length + ":\""); output.writeBytes(bytes); text("\";"); }
        void key(Memory key) {
            if (key.getRealType() == Memory.Type.INT) text("i:" + key.toLong() + ";");
            else string(key.getBinaryBytes(StandardCharsets.UTF_8));
        }

        void value(Memory memory) throws Throwable {
            boolean reference = memory.isReference();
            Object identity = null;
            while (memory.isReference()) {
                identity = memory;
                memory = ((ReferenceMemory) memory).getValue();
            }
            if (memory.isObject() && !memory.isResource()) identity = memory.toValue(ObjectMemory.class).value;
            int id = ++index;
            if (identity != null) {
                Integer previous = identities.get(identity);
                if (previous != null) {
                    if (reference) index--;
                    text((reference ? "R:" : "r:") + previous + ";");
                    return;
                }
                identities.put(identity, id);
            }
            switch (memory.type) {
                case NULL: text("N;"); break;
                case BOOL: text(memory.toBoolean() ? "b:1;" : "b:0;"); break;
                case INT: text("i:" + memory.toLong() + ";"); break;
                case DOUBLE: text("d:" + doubleText(memory.toDouble()) + ";"); break;
                case STRING: string(memory.getBinaryBytes(StandardCharsets.UTF_8)); break;
                case ARRAY:
                    ArrayMemory array = memory.toValue(ArrayMemory.class);
                    if (!reference && activeArrays.containsKey(array)) { text("N;"); break; }
                    Boolean previous = activeArrays.put(array, Boolean.TRUE);
                    actions.push(() -> { if (previous == null) activeArrays.remove(array); });
                    text("a:"); entries(array, null);
                    break;
                case OBJECT:
                    if (memory.isResource()) text("i:0;");
                    else object(memory.toValue(ObjectMemory.class));
                    break;
                default: text("i:0;"); break;
            }
        }

        String doubleText(double value) {
            if (Double.isNaN(value)) return "NAN";
            if (Double.isInfinite(value)) return value > 0 ? "INF" : "-INF";
            if (value == 0) return Double.doubleToRawLongBits(value) < 0 ? "-0" : "0";
            int precision = env.getConfigValue("serialize_precision", LongMemory.valueOf(-1)).toInteger();
            BigDecimal decimal = precision < 0 ? new BigDecimal(Double.toString(value))
                    : new BigDecimal(String.format(Locale.ROOT, "% ." + Math.max(1, Math.min(precision, 100)) + "G", value).trim());
            decimal = decimal.stripTrailingZeros();
            int exponent = decimal.precision() - decimal.scale() - 1;
            int threshold = precision < 0 ? 17 : Math.max(1, precision);
            if (exponent >= -4 && exponent < threshold) return decimal.toPlainString();
            String mantissa = decimal.movePointLeft(exponent).toPlainString();
            if (!mantissa.contains(".")) mantissa += ".0";
            return mantissa + "E" + (exponent >= 0 ? "+" : "") + exponent;
        }

        void entries(ArrayMemory array, String excluded) throws Throwable {entries(array, excluded, false);}
        void entries(ArrayMemory array, String excluded, boolean skipUndefined) throws Throwable {
            int count = array.size() - (excluded != null && array.containsKey(excluded) ? 1 : 0);
            if (skipUndefined) {
                count = 0;
                ForeachIterator values = array.foreachIterator(false, false);
                while (values.next()) if (!values.getValue().isUndefined()
                        && (excluded == null || !excluded.equals(values.getMemoryKey().toString()))) count++;
            }
            text(count + ":{");
            ForeachIterator iterator = array.foreachIterator(false, false);
            actions.push(new Action() {
                public void run() throws Throwable {
                    while (iterator.next()) {
                        if (skipUndefined && iterator.getValue().isUndefined()) continue;
                        if (excluded != null && excluded.equals(iterator.getMemoryKey().toString())) continue;
                        key(iterator.getMemoryKey());
                        actions.push(this);
                        value(iterator.getValue());
                        return;
                    }
                    text("}");
                }
            });
        }

        void object(ObjectMemory memory) throws Throwable {
            IObject object = memory.value;
            ClassEntity clazz = object.getReflection();
            if (object instanceof Closure || object instanceof Generator) {
                env.exception(trace, BaseException.class, "Serialization of '%s' is not allowed", clazz.getName());
            }
            MethodEntity modern = clazz.findMethod("__serialize");
            ArrayMemory properties = object.getProperties();
            if (modern != null) {
                Memory data = invoke(object, modern);
                if (!data.isArray()) env.exception(trace, BaseTypeError.class, "%s::__serialize() must return an array", clazz.getName());
                properties = data.toValue(ArrayMemory.class);
            } else if (object instanceof Serializable) {
                Memory data = ((Serializable) object).serialize(env);
                if (data.isNull()) { text("N;"); return; }
                if (!data.isString()) env.exception(trace, BaseException.class, "%s::serialize() must return a string or NULL", clazz.getName());
                byte[] name = clazz.getName().getBytes(StandardCharsets.UTF_8);
                byte[] payload = data.getBinaryBytes(StandardCharsets.UTF_8);
                text("C:" + name.length + ":\""); output.writeBytes(name); text("\":" + payload.length + ":{");
                output.writeBytes(payload); text("}"); return;
            } else if (clazz.methodMagicSleep != null) {
                Memory selected = invoke(object, clazz.methodMagicSleep);
                if (!selected.isArray()) {
                    env.error(trace, ErrorType.E_WARNING, "%s::__sleep() should return an array only containing the names of instance-variables to serialize", clazz.getName());
                    text("N;"); return;
                }
                properties = new ArrayMemory();
                ForeachIterator iterator = selected.getNewIterator(env, false, false);
                while (iterator.next()) {
                    String name = iterator.getValue().toString();
                    PropertyEntity property = clazz.findProperty(name);
                    String specific = property == null ? name : property.getSpecificName();
                    ReferenceMemory entry = object.getProperties().getByScalar(specific);
                    if (entry == null || entry.isUndefined()) env.error(trace, ErrorType.E_WARNING, "serialize(): \"%s\" returned as member variable from __sleep() but does not exist", name);
                    else properties.put(specific, entry.getValue());
                }
            }
            String excluded = object instanceof IncompleteClass ? "__PHP_Incomplete_Class_Name" : null;
            String className = excluded == null ? clazz.getName() : properties.valueOfIndex(excluded).toString();
            byte[] name = className.getBytes(StandardCharsets.UTF_8);
            text("O:" + name.length + ":\""); output.writeBytes(name); text("\":");
            entries(properties, excluded, properties == object.getProperties());
        }

        Memory invoke(IObject object, MethodEntity method, Memory... args) throws Throwable {
            env.pushCall(trace, object, method.getName(), args);
            try { return method.invokeDynamic(object, env, trace, args); }
            finally { env.popCall(); }
        }
    }

    @FunctionalInterface private interface Action { void run() throws Throwable; }
    private static final class InvalidData extends RuntimeException {}
    private static final class Slot {
        final Memory value;
        final List<ReferenceMemory> placements = new ArrayList<>();
        ReferenceMemory reference;
        Slot(Memory value) { this.value = value; }
        Memory stored() { return reference == null ? value : reference; }
        void promote() {
            if (reference == null) {
                reference = new ReferenceMemory(value);
                for (ReferenceMemory placement : placements) placement.assignRef(reference);
            }
        }
    }
    private static final class Frame {
        final ArrayMemory entries;
        final Action finish;
        final boolean propertyKeys;
        final ClassEntity propertyClass;
        int remaining;
        Frame(ArrayMemory entries, int remaining, Action finish, boolean propertyKeys, ClassEntity propertyClass) {
            this.entries = entries; this.remaining = remaining; this.finish = finish; this.propertyKeys = propertyKeys;
            this.propertyClass = propertyClass;
        }
        String propertyKey(String key) {
            if (propertyClass == null || entries.getByScalar(key) != null) return key;
            String name = key;
            if (key.startsWith("\0")) {
                int split = key.indexOf('\0', 1);
                if (split < 0) return key;
                name = key.substring(split + 1);
                String scope = key.substring(1, split);
                // These frames belong to PHP85 restoration (including its legacy-wire bridge).
                if (!scope.equals("*") && !php.runtime.common.PhpClassNames.equals(
                        propertyClass.getName(), scope, PhpLanguageProfile.PHP_8_5)) return key;
            }
            PropertyEntity property = propertyClass.findProperty(name);
            if (property != null) return property.getSpecificName();
            return propertyClass.findStaticProperty(name) == null ? key : name;
        }
    }

    private static final class Reader {
        final Environment env;
        final TraceInfo trace;
        final byte[] bytes;
        final List<Slot> slots = new ArrayList<>();
        final Deque<Frame> frames = new ArrayDeque<>();
        final List<Action> callbacks = new ArrayList<>();
        final Memory allowed;
        final Memory depth;
        final long maxDepth;
        int pos;

        Reader(Environment env, TraceInfo trace, byte[] bytes, ArrayMemory options) {
            this.env = env; this.trace = trace; this.bytes = bytes;
            allowed = options.valueOfIndex("allowed_classes").toValue();
            depth = options.valueOfIndex("max_depth").toValue();
            maxDepth = depth.isUndefined() ? 4096 : depth.toLong();
        }

        Memory read() throws Throwable {
            if (!depth.isUndefined() && depth.type != Memory.Type.INT) {
                env.exception(trace, BaseTypeError.class, "unserialize(): Option \"max_depth\" must be of type int");
            }
            if (maxDepth < 0) env.exception(trace, BaseValueError.class, "unserialize(): Option \"max_depth\" must be greater than or equal to 0");
            if (!allowed.isUndefined() && allowed.type != Memory.Type.BOOL && !allowed.isArray()) {
                env.exception(trace, BaseTypeError.class, "unserialize(): allowed_classes must be of type array|bool");
            }
            if (allowed.isArray()) {
                ForeachIterator iterator = allowed.getNewIterator(env, false, false);
                while (iterator.next()) if (!iterator.getValue().isString()) {
                    env.exception(trace, BaseTypeError.class, "unserialize(): Option \"allowed_classes\" must be an array of class names");
                }
            }
            try {
                Slot root = value();
                while (!frames.isEmpty()) {
                    Frame frame = frames.peek();
                    if (frame.remaining == 0) {
                        expect('}'); frames.pop();
                        if (frame.finish != null) callbacks.add(frame.finish);
                        continue;
                    }
                    Memory key = key();
                    ReferenceMemory placement = null;
                    if (frame.propertyKeys) {
                        // Warn before parsing the value: nested objects can autoload or run callbacks.
                        String storageKey = frame.propertyKey(key.toString());
                        if (frame.propertyClass != null)
                            frame.propertyClass.checkDynamicPropertyRestoration(env, trace, frame.entries, storageKey);
                        // Preserve existing declaration guards, and publish a new NULL placeholder.
                        placement = frame.entries.getByScalarOrCreate(storageKey, Memory.NULL);
                    }
                    Slot slot = value();
                    if (frame.propertyKeys) {
                        if (slot.reference == null) placement.assign(slot.value);
                        else placement.assignRef(slot.reference);
                    } else {
                        placement = frame.entries.put(ArrayMemory.toKey(key), slot.stored());
                    }
                    slot.placements.add(placement);
                    frame.remaining--;
                }
                if (pos != bytes.length) env.error(trace, ErrorType.E_WARNING, "unserialize(): Extra data starting at offset %s of %s bytes", pos, bytes.length);
                for (Action callback : callbacks) callback.run();
                return root.value.toImmutable();
            } catch (InvalidData invalid) {
                env.error(trace, ErrorType.E_WARNING, "unserialize(): Error at offset %s of %s bytes", pos, bytes.length);
                return Memory.FALSE;
            }
        }

        int take() { if (pos >= bytes.length) throw new InvalidData(); return bytes[pos++] & 255; }
        void expect(char token) { if (take() != token) throw new InvalidData(); }
        String token(char delimiter) {
            int start = pos;
            while (pos < bytes.length && bytes[pos] != delimiter) pos++;
            if (pos == bytes.length) throw new InvalidData();
            String token = new String(bytes, start, pos - start, StandardCharsets.US_ASCII); pos++;
            return token;
        }
        int size(char delimiter) {
            try { int size = Integer.parseInt(token(delimiter)); if (size < 0 || size > bytes.length) throw new InvalidData(); return size; }
            catch (NumberFormatException invalid) { throw new InvalidData(); }
        }
        byte[] string(char end) {
            int size = size(':'); expect('"');
            if (size > bytes.length - pos) throw new InvalidData();
            byte[] value = Arrays.copyOfRange(bytes, pos, pos + size); pos += size;
            expect('"'); expect(end); return value;
        }
        Memory key() {
            int type = take(); expect(':');
            if (type == 's') return new BinaryMemory(string(';'));
            if (type == 'i') {
                return LongMemory.valueOf(integer());
            }
            throw new InvalidData();
        }
        long integer() {
            String number = token(';');
            try { return Long.parseLong(number); }
            catch (NumberFormatException invalid) {
                int start = !number.isEmpty() && (number.charAt(0) == '-' || number.charAt(0) == '+') ? 1 : 0;
                if (start == number.length()) throw new InvalidData();
                for (int i = start; i < number.length(); i++) {
                    if (number.charAt(i) < '0' || number.charAt(i) > '9') throw new InvalidData();
                }
                return number.charAt(0) == '-' ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
        }
        Slot register(Memory value) { Slot slot = new Slot(value); slots.add(slot); return slot; }
        void frame(ArrayMemory entries, int count, Action finish) {
            frame(entries, count, finish, false);
        }
        void frame(ArrayMemory entries, int count, Action finish, boolean propertyKeys) {
            frame(entries, count, finish, propertyKeys, null);
        }
        void frame(ArrayMemory entries, int count, Action finish, boolean propertyKeys, ClassEntity propertyClass) {
            if (maxDepth != 0 && frames.size() >= maxDepth) {
                env.error(trace, ErrorType.E_WARNING, "unserialize(): Maximum depth of %s exceeded", maxDepth);
                throw new InvalidData();
            }
            frames.push(new Frame(entries, count, finish, propertyKeys, propertyClass));
        }
        boolean allowed(String name) {
            if (allowed.isUndefined() || allowed == Memory.TRUE) return true;
            if (!allowed.isArray()) return false;
            ForeachIterator iterator = allowed.getNewIterator(env, false, false);
            while (iterator.next()) if (name.equalsIgnoreCase(iterator.getValue().toString())) return true;
            return false;
        }

        Slot value() throws Throwable {
            int type = take();
            if (type == 'N') { expect(';'); return register(Memory.NULL); }
            expect(':');
            switch (type) {
                case 'R': case 'r':
                    int id = size(';');
                    if (id == 0 || id > slots.size()) throw new InvalidData();
                    Slot target = slots.get(id - 1);
                    if (type == 'R') { target.promote(); return target; }
                    if (!target.value.isObject()) throw new InvalidData();
                    return register(target.value);
                case 'b':
                    String bool = token(';');
                    if (!bool.equals("0") && !bool.equals("1")) throw new InvalidData();
                    return register(bool.equals("1") ? Memory.TRUE : Memory.FALSE);
                case 'i':
                    return register(LongMemory.valueOf(integer()));
                case 'd':
                    String number = token(';');
                    if (!number.equals("NAN") && !number.equals("INF") && !number.equals("-INF")
                            && !SERIALIZED_NUMBER.matcher(number).matches()) throw new InvalidData();
                    try { return register(DoubleMemory.valueOf(number.equals("NAN") ? Double.NaN : number.equals("INF")
                            ? Double.POSITIVE_INFINITY : number.equals("-INF") ? Double.NEGATIVE_INFINITY : Double.parseDouble(number))); }
                    catch (NumberFormatException invalid) { throw new InvalidData(); }
                case 's': return register(new BinaryMemory(string(';')));
                case 'a':
                    int count = size(':'); expect('{');
                    ArrayMemory array = new ArrayMemory(); Slot slot = register(array); frame(array, count, null); return slot;
                case 'O': case 'C': return object(type == 'C');
                default: throw new InvalidData();
            }
        }

        Slot object(boolean custom) throws Throwable {
            String name = new String(string(':'), StandardCharsets.UTF_8);
            boolean permitted = allowed(name);
            ClassEntity clazz = permitted ? env.fetchClass(name, true) : null;
            boolean incomplete = clazz == null;
            if (incomplete) clazz = env.fetchClass("__PHP_Incomplete_Class", true);
            IObject object = clazz.newObjectWithoutConstruct(env);
            if (object == null) env.exception(trace, BaseException.class, "Unserialization of '%s' is not allowed", name);
            Slot slot = register(ObjectMemory.valueOf(object));
            if (incomplete) object.getProperties().put("__PHP_Incomplete_Class_Name", StringMemory.valueOf(name));
            int count = size(':'); expect('{');
            if (custom) {
                if (count > bytes.length - pos) throw new InvalidData();
                Memory payload = new BinaryMemory(Arrays.copyOfRange(bytes, pos, pos + count)); pos += count; expect('}');
                if (!incomplete && object instanceof Serializable) ((Serializable) object).unserialize(env, payload);
                else env.error(trace, ErrorType.E_WARNING, "Class %s has no unserializer", name);
                return slot;
            }
            ClassEntity reflected = clazz;
            MethodEntity modern = incomplete ? null : clazz.findMethod("__unserialize");
            ArrayMemory payload = modern == null ? object.getProperties() : new ArrayMemory();
            frame(payload, count, () -> {
                if (modern != null) {
                    invoke(object, modern, payload);
                } else {
                    if (!incomplete && reflected.methodMagicWakeup != null) invoke(object, reflected.methodMagicWakeup);
                }
            }, modern == null, modern == null && !incomplete ? reflected : null);
            return slot;
        }
        void invoke(IObject object, MethodEntity method, Memory... args) throws Throwable {
            env.pushCall(trace, object, method.getName(), args);
            try { method.invokeDynamic(object, env, trace, args); }
            finally { env.popCall(); }
        }
    }
}
