package php.runtime.memory.output.serialization;

import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.core.StringFunctions;
import php.runtime.lang.*;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseValueError;
import php.runtime.lang.spl.Serializable;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;
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
        return new PhpByteStringMemory(writer.output.toByteArray());
    }

    private static php.runtime.PhpRuntimeOwnership.NativeBody nativeParserBody(Environment env) {
        var body = php.runtime.PhpRuntimeOwnership.currentNativeBody(env);
        if (body != null && (body.member().getDeclaringClass() != PhpSerialization.class
                || !body.member().getName().equals("unserialize")))
            throw new IllegalArgumentException("Parser requires its exact native invocation body");
        return body;
    }

    public static Memory unserialize(Environment env, TraceInfo trace, Memory value) throws Throwable {
        nativeParserBody(env);
        if (env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.JPHP_LEGACY) {
            Memory actual = value.toValue();
            return StringFunctions.unserialize(env, trace,
                    actual instanceof PhpByteStringMemory ? actual.toBinaryString() : value.toString());
        }
        return unserialize(env, trace, value, new ArrayMemory());
    }

    public static Memory unserialize(Environment env, TraceInfo trace, Memory value, Memory options) throws Throwable {
        nativeParserBody(env);
        byte[] bytes = inputBytes(env, trace, value.toValue());
        if (!options.isArray()) {
            env.exception(trace, BaseTypeError.class, "unserialize(): Argument #2 ($options) must be of type array");
        }
        var context = php.runtime.PhpPropertyWriteContext.enter(env, trace, true);
        try { return new Reader(env, trace, bytes, options.toValue(ArrayMemory.class)).read(); }
        finally { php.runtime.PhpPropertyWriteContext.leave(context); }
    }

    /** Ordinary SPL wire keeps one reference-id space across its flags, storage and own properties. */
    public static Memory serializeSplArrayObject(Environment env,TraceInfo trace,Memory flags,Memory storage,Memory properties) throws Throwable {
        requireOrdinarySpl(env);Writer writer=new Writer(env,trace);writer.text("x:");
        writer.value(flags);while(!writer.actions.isEmpty())writer.actions.pop().run();
        if(storage!=null){writer.value(storage.toValue());while(!writer.actions.isEmpty())writer.actions.pop().run();writer.text(";");}
        writer.text("m:");writer.value(properties.toValue());while(!writer.actions.isEmpty())writer.actions.pop().run();
        return new PhpByteStringMemory(writer.output.toByteArray());
    }
    private static void requireOrdinarySpl(Environment env) {
        if(php.runtime.PhpOwnershipExecution.current(env)!=null || php.runtime.PhpRuntimeOwnership.current(env)!=null)
            throw new IllegalArgumentException("SPL wire requires its ordinary invocation");
    }
    /** One actual byte Reader also retains cross-segment CELL and OBJECT identities. */
    public record PrefixRead(Memory value,int nextOffset) {}
    public static final class SplPrefixReader {
        private final Reader reader;
        public SplPrefixReader(Environment env,TraceInfo trace,byte[] bytes) {
            requireOrdinarySpl(env);reader=new Reader(env,trace,bytes,new ArrayMemory());reader.prefix=true;
        }
        public PrefixRead read(int offset) throws Throwable {
            requireOrdinarySpl(reader.env);
            if(offset<0 || offset>=reader.bytes.length)throw new IllegalArgumentException(Integer.toString(offset));
            var context=php.runtime.PhpPropertyWriteContext.enter(reader.env,reader.trace,true);
            try {
                reader.pos=offset;Memory result=reader.read();
                if(reader.prefixFailed)throw new IllegalArgumentException(Integer.toString(reader.pos));
                return new PrefixRead(result,reader.pos);
            } finally {php.runtime.PhpPropertyWriteContext.leave(context);}
        }
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

    /** Native Serializable bridges do not push a PHP method frame themselves. */
    static Memory invokeSerializableHook(Environment env, TraceInfo trace, IObject object, String name, Memory... args) {
        MethodEntity method = object.getReflection().findMethod(name);
        String declaration = method == null ? object.getReflection().getName() : method.getClazzName();
        env.pushCall(trace, object, args, name, declaration, object.getReflection().getName());
        try {
            if (method != null) return method.invokeDynamic(object, env, trace, args);
            // Preserve Java Serializable implementations whose method has no PHP metadata.
            return name.equals("serialize") ? ((Serializable) object).serialize(env, args)
                    : ((Serializable) object).unserialize(env, args);
        } catch (Throwable failure) {
            return PhpSerialization.<RuntimeException>throwSerializableFailure(failure);
        } finally {
            env.popCall();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> Memory throwSerializableFailure(Throwable failure) throws T { throw (T) failure; }

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
        final IdentityHashMap<Object, Boolean> activeArrays = new IdentityHashMap<>();
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
            var ownership = php.runtime.PhpOwnershipExecution.current(env);
            var runtime=php.runtime.PhpRuntimeOwnership.current(env);
            var runtimeDomain=runtime != null ? runtime.domain() : ownership != null ? ownership.domain() : null;
            boolean reference = ownership == null && memory.isReference();
            Object identity = null;
            while (memory.isReference()) {
                ReferenceMemory cell = (ReferenceMemory) memory;
                var runtimeCell=cell.getRuntimeCell();
                var runtimeHolder=cell.getRuntimeHolder();
                if (runtimeCell != null) {
                    if (runtimeCell.authority().domain() != runtimeDomain)
                        throw new IllegalArgumentException("Serialization CELL belongs to another runtime authority");
                    // One physical owner does not make the payload a shared PHP reference.
                    reference=runtimeCell.node().incoming() > 1;identity=reference ? cell : null;
                } else if (runtimeHolder != null) {
                    if (runtimeHolder.domain() != runtimeDomain || !runtimeHolder.matches(cell))
                        throw new IllegalArgumentException("Serialization holder is foreign or no longer identifies its carrier");
                    runtimeHolder.domain().currentContext();reference=false;identity=null;
                } else if (cell.getOwnershipCell() != null && runtimeDomain != null) {
                    // Native BODY producers can publish a certified CELL without an explicit Session.
                    var arrays = runtime != null ? runtime.arrays() : ownership.arrays();
                    var certificate = arrays.certificate(cell);
                    reference = certificate.node().incoming() > 1;
                    identity = reference ? cell : null;
                } else if (ownership == null) identity = memory;
                else if (cell.isCanonicalReferenceCell()) {
                    // Native serialize ignores a REFERENCE with only one surviving PHP owner.
                    var certificate = ownership.arrays().certificate(cell);
                    if (certificate.node().incoming() > 1) { reference = true; identity = cell; }
                } else if (cell.getOwnershipEntry() == null)
                    throw new IllegalArgumentException("Serialization address lacks its actual physical owner");
                memory = cell.getValue();
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
                    Object storage = array.getStorageIdentity();
                    if (!reference && activeArrays.containsKey(storage)) { text("N;"); break; }
                    Boolean previous = activeArrays.put(storage, Boolean.TRUE);
                    actions.push(() -> { if (previous == null) activeArrays.remove(storage); });
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
            IObject object = php.runtime.PhpLazyObjects.observe(env,memory.value,php.runtime.PhpLazyObjects.Observation.SERIALIZE);
            ClassEntity clazz = object.getReflection();
            if(object instanceof php.runtime.lang.BaseEnum actual) {
                byte[] name=(clazz.getName()+":"+actual.enumCaseName()).getBytes(StandardCharsets.UTF_8);
                text("E:"+name.length+":\"");output.writeBytes(name);text("\";");return;
            }
            if (clazz.isAnonymous() || object instanceof Closure || object instanceof Generator || object instanceof php.runtime.lang.PhpWeakReference || object instanceof php.runtime.lang.PhpFiber) {
                env.exception(trace, BaseException.class, "Serialization of '%s' is not allowed", clazz.getName());
            }
            MethodEntity modern = clazz.findMethod("__serialize");
            ArrayMemory properties = object.getProperties();
            if (modern != null) {
                Memory data = invoke(object, modern);
                if (!data.isArray()) env.exception(trace, BaseTypeError.class, "%s::__serialize() must return an array", clazz.getName());
                properties = data.toValue(ArrayMemory.class);
            } else if (object instanceof Serializable) {
                Memory data = invokeSerializableHook(env, trace, object, "serialize");
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
    private static final class Restoration implements Action {
        final IObject object;
        final Action callback;
        final ArrayMemory payload;
        boolean queued, completed;
        Restoration(IObject object, Action callback, ArrayMemory payload) { this.object = object; this.callback = callback; this.payload = payload; }
        public void run() throws Throwable { callback.run(); completed = true; }
    }
    private static final class InvalidData extends RuntimeException {}
    private static final class Placement {
        final ArrayMemory entries;
        final Object key;
        final php.runtime.PhpOwnershipArrays.RestorationAddress owned;
        Placement(ArrayMemory entries, Object key, php.runtime.PhpOwnershipArrays.RestorationAssembly assembly) {
            this.entries = entries; this.key = key; owned = assembly == null ? null : assembly.address(key);
        }
        ReferenceMemory current() { return entries.getByScalar(key); }
    }
    private static final class Slot {
        final Memory value;
        Placement placement;
        ReferenceMemory reference;
        php.runtime.PhpOwnershipExecution.Entry ownership;
        php.runtime.PhpOwnershipFramePublisher producer;
        php.runtime.PhpRuntimeOwnership.NativeProducer nativeProducer;
        Slot(Memory value) { this.value = value; }
        Memory current() { return placement != null ? placement.current().toValue()
                : nativeProducer != null ? nativeProducer.raw().toValue() : value; }
        Memory stored() { return reference == null ? value : reference; }
        void promote() throws Throwable {
            // An id denotes its original zval address, whose current payload can be replaced by a duplicate key.
            if (placement != null && placement.owned != null) {
                var before = placement.owned.current();
                if (before.binding().role() == php.runtime.PhpOwnershipValues.Role.CELL)
                    reference = (ReferenceMemory) before.binding().rawValue();
                else try (var publication = placement.owned.preparePromotion(before)) {
                    reference = publication.cell(); publication.store(); publication.commit();
                }
            } else if (placement != null) reference = php.runtime.PhpReadonlyProperty.readerReference(placement.current());
            else if (reference == null && ownership != null)
                reference = (ReferenceMemory) ownership.promoteFreshStage(ownership.session().value(value)).rawValue();
            else if (reference == null && nativeProducer != null) reference = nativeProducer.promote();
            else if (reference == null) reference = new ReferenceMemory(value).getReferenceForBinding();
        }
    }
    private static final class PendingRestoration {
        final Action completion, abort, close;
        boolean finished;
        PendingRestoration(Action completion, Action abort, Action close) {
            this.completion = completion; this.abort = abort; this.close = close;
        }
        void complete() throws Throwable { if (!finished) { finished = true; completion.run(); } }
        void abort() throws Throwable { if (!finished) { finished = true; abort.run(); } }
    }
    private static final class Frame {
        PendingRestoration validation;
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
        final List<Restoration> restorations = new ArrayList<>();
        final List<PendingRestoration> pending = new ArrayList<>();
        // Parser addresses and assemblies borrow their physical owner; wire ids acquire no PHP owners.
        final IdentityHashMap<ArrayMemory, php.runtime.PhpOwnershipArrays.RestorationAssembly> assemblies = new IdentityHashMap<>();
        final Memory allowed;
        final Memory depth;
        final long maxDepth;
        final php.runtime.PhpOwnershipExecution.Entry ownership;
        final php.runtime.PhpRuntimeOwnership.NativeArena nativeArena;
        final IdentityHashMap<Memory, php.runtime.PhpRuntimeOwnership.NativeProducer> nativeAllocations = new IdentityHashMap<>();
        php.runtime.PhpOwnershipFrame cleanupFrame;
        int cleanupIndex;
        Slot resultSlot;
        int pos;
        boolean prefix,prefixFailed;

        Reader(Environment env, TraceInfo trace, byte[] bytes, ArrayMemory options) {
            this.env = env; this.trace = trace; this.bytes = bytes;
            ownership = php.runtime.PhpOwnershipExecution.currentEntry(env);
            if (php.runtime.PhpOwnershipExecution.current(env) != null && ownership == null)
                throw new IllegalArgumentException("Unserialization requires its actual invocation frame");
            var body = nativeParserBody(env);
            if (ownership != null && body != null)
                throw new IllegalArgumentException("Parser cannot borrow two invocation protocols");
            nativeArena = body == null ? null : body.arena();
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
            try { return parse(); }
            catch (Throwable failure) {
                try { abortPending(); }
                catch (Throwable cleanup) {
                    if (ownership != null) failure = ownership.session().domain().graph().composeFailure(failure, cleanup);
                    else failure.addSuppressed(cleanup);
                }
                if (ownership != null || nativeArena != null) {
                    suppressUnfinishedRestorations();
                    try { releaseFailedResult(); }
                    catch (Throwable cleanup) {
                        var domain = ownership != null ? ownership.session().domain()
                                : php.runtime.PhpRuntimeOwnership.current(env).domain();
                        failure = domain.graph().composeFailure(failure, cleanup);
                    }
                }
                drainCallbacks(failure); throw failure;
            }
            finally {
                try {
                    for (Restoration restoration : restorations)
                        if (!restoration.completed) restoration.object.doFinalize();
                } finally {
                    try { if (cleanupFrame != null) cleanupFrame.close(); }
                    finally {
                        try { for (PendingRestoration restoration : pending) restoration.close.run(); }
                        finally { for (var assembly : assemblies.values()) assembly.close(); }
                    }
                }
            }
        }
        Memory parse() throws Throwable {
            try {
                Slot root = value();
                while (!frames.isEmpty()) {
                    Frame frame = frames.peek();
                    if (frame.remaining == 0) {
                        expect('}'); frames.pop();
                        if (frame.finish != null) queueRestoration(frame.finish);
                        if (frame.validation != null) frame.validation.complete();
                        continue;
                    }
                    Memory key = key();
                    Object storageKey = ArrayMemory.toKey(key);
                    ReferenceMemory placement = null;
                    ReferenceMemory.PropertyRestoration ordinary = null;
                    PendingRestoration validation = null;
                    if (frame.propertyKeys) {
                        // Warn before parsing the value: nested objects can autoload or run callbacks.
                        storageKey = frame.propertyKey(key.toString());
                        if (frame.propertyClass != null)
                            frame.propertyClass.checkDynamicPropertyRestoration(env, trace, frame.entries, (String) storageKey);
                        // Preserve existing declaration guards, and publish a new NULL placeholder.
                        if (ownership == null && nativeArena == null) {
                            placement = frame.entries.getByScalarOrCreate(storageKey, Memory.NULL);
                            ordinary = placement.beginPropertyRestoration(Memory.NULL);
                            var handle = ordinary;
                            validation = new PendingRestoration(handle::complete, handle::abort, handle::close);
                            pending.add(validation);
                        }
                    }
                    Placement destination = new Placement(frame.entries, storageKey, assemblies.get(frame.entries));
                    if (ownership != null || nativeArena != null) preparePlacement(destination);
                    boolean referenceAlias = pos < bytes.length && bytes[pos] == 'R';
                    Slot slot = value(destination);
                    boolean nested = frames.peek() != frame;
                    if (ownership != null && frame.propertyKeys && nested) validation = publishPendingPlacement(destination, slot);
                    else if (ownership != null || nativeArena != null) publishPlacement(destination, slot, referenceAlias);
                    else if (frame.propertyKeys) ordinary.store(slot.stored());
                    else {
                        placement = frame.entries.put(storageKey, slot.stored());
                    }
                    if (slot.placement == null && slot.reference == null) slot.placement = destination;
                    frame.remaining--;
                    if (validation != null) {
                        if (nested) frames.peek().validation = validation;
                        else validation.complete();
                    }
                }
                if (!prefix && pos != bytes.length) env.error(trace, ErrorType.E_WARNING, "unserialize(): Extra data starting at offset %s of %s bytes", pos, bytes.length);
                drainCallbacks();
                if (nativeArena != null) return root.nativeProducer.publishResult();
                if (ownership != null && root.reference != null)
                    return ownership.stageValueResultFromCell(root.reference).toImmutable();
                return root.value.toImmutable();
            } catch (InvalidData invalid) {
                abortPending();
                prefixFailed=true;
                if(!prefix) env.error(trace, ErrorType.E_WARNING, "unserialize(): Error at offset %s of %s bytes", pos, bytes.length);
                // The failed return subtree is released before VAR_DTOR; completed hooks have their own queue owners.
                if (ownership != null || nativeArena != null) { suppressUnfinishedRestorations(); releaseFailedResult(); }
                drainCallbacks();
                return nativeArena == null ? Memory.FALSE : nativeArena.temp(Memory.FALSE).publishResult();
            }
        }

        void abortPending() throws Throwable {
            for (int i = pending.size() - 1; i >= 0; i--) pending.get(i).abort();
        }
        PendingRestoration publishPendingPlacement(Placement destination, Slot slot) throws Throwable {
            var source = slot.producer;
            if (source == null) throw new IllegalArgumentException("Nested restoration lost its fresh producer");
            ArrayMemory child = slot.value.isArray() ? slot.value.toValue(ArrayMemory.class)
                    : slot.value.toValue(ObjectMemory.class).value.getProperties();
            php.runtime.PhpOwnershipArrays.RestorationCompletion handle;
            try (var publication = destination.owned.preparePendingMoveFrom(destination.owned.current(),
                    source, source.current(), assemblies.get(child))) {
                publication.store(); publication.commit(); handle = publication.pendingCompletion();
            }
            slot.producer = null;
            var validation = new PendingRestoration(() -> {
                try (var publication = handle.prepare(destination.owned.current())) {
                    publication.store(); publication.commit();
                } catch (BaseTypeError failure) {
                    try (var publication = handle.prepareUnset(destination.owned.current())) {
                        publication.store(); publication.commit();
                    } catch (Throwable cleanup) {
                        throw ownership.session().domain().graph().composeFailure(failure, cleanup);
                    }
                    throw failure;
                }
            }, () -> handle.abort(destination.owned.current()), handle::close);
            pending.add(validation); return validation;
        }
        void suppressUnfinishedRestorations() {
            for (Restoration restoration : restorations) if (!restoration.queued) restoration.object.doFinalize();
        }
        void releaseFailedResult() throws Throwable {
            if (resultSlot != null && resultSlot.nativeProducer != null) {
                var source = resultSlot.nativeProducer; resultSlot.nativeProducer = null; source.close();
            }
            if (resultSlot != null && resultSlot.producer != null) {
                var source = resultSlot.producer; resultSlot.producer = null;
                try (var publication = source.prepare(source.current(), ownership.session().value(Memory.UNDEFINED))) { publication.commit(); }
            }
        }
        php.runtime.PhpOwnershipFramePublisher cleanupSlot() {
            if (cleanupFrame == null) cleanupFrame = new php.runtime.PhpOwnershipFrame(ownership.session().domain().graph());
            return ownership.session().domain().heap().framePublisher(cleanupFrame.temporary("parser-cleanup:" + cleanupIndex++));
        }
        void releaseCleanup(php.runtime.PhpOwnershipFramePublisher publisher) throws Throwable {
            try (var publication = publisher.prepare(publisher.current(), ownership.session().value(Memory.UNDEFINED))) { publication.commit(); }
        }
        void preparePlacement(Placement destination) throws Throwable {
            if (destination.owned == null) throw new IllegalArgumentException("Parser placement has no fresh physical assembly");
            var before = destination.owned.current();
            if (before.binding() == null) {
                var binding = ownership != null ? ownership.session().value(Memory.NULL)
                        : php.runtime.PhpOwnershipValues.bindValue(php.runtime.PhpRuntimeOwnership.current(env).domain(), Memory.NULL, null);
                try (var publication = destination.owned.preparePut(before, binding)) {
                    publication.store(); publication.commit();
                }
            } else if (nativeArena != null) {
                var cleanup = nativeArena.empty();
                try (var publication = destination.owned.prepareClearTo(before, cleanup, cleanup.current())) {
                    publication.store(); publication.commit();
                }
                callbacks.add(cleanup::close);
            } else {
                var cleanup = cleanupSlot();
                try (var publication = destination.owned.prepareClearTo(before, cleanup, cleanup.current())) {
                    publication.store(); publication.commit();
                }
                // VAR_DTOR entries are processed in creation order, interleaved with completed hooks.
                callbacks.add(() -> releaseCleanup(cleanup));
            }
        }
        void publishPlacement(Placement destination, Slot slot, boolean referenceAlias) throws Throwable {
            if (nativeArena != null) {
                var before = destination.owned.current();
                if (!referenceAlias && slot.nativeProducer != null) {
                    var source = slot.nativeProducer;
                    try (var publication = destination.owned.prepareMoveFrom(before, source, source.current())) {
                        publication.store(); publication.commit();
                    }
                    slot.nativeProducer = null;
                } else {
                    if (slot.reference == null) throw new IllegalArgumentException("Native placement lost its actual TEMP producer");
                    var domain = php.runtime.PhpRuntimeOwnership.current(env).domain();
                    var binding = php.runtime.PhpOwnershipValues.bindCell(domain, slot.reference,
                            php.runtime.PhpRuntimeOwnership.current(env).arrays().certificate(slot.reference).node());
                    try (var publication = destination.owned.prepareReference(before, binding)) {
                        publication.store(); publication.commit();
                    }
                }
                return;
            }
            var session = ownership.session();
            var before = destination.owned.current();
            var binding = slot.reference == null ? session.value(slot.value) : session.cell(slot.reference);
            var source = !referenceAlias ? slot.producer : null;
            if (source != null) {
                try (var publication = destination.owned.prepareMoveFrom(before, source, source.current())) {
                    publication.store(); publication.commit();
                }
                slot.producer = null;
            } else try (var publication = slot.reference == null ? destination.owned.preparePut(before, binding)
                    : destination.owned.prepareReference(before, binding)) {
                publication.store(); publication.commit();
            }
        }
        void queueRestoration(Action action) throws Throwable {
            if (action instanceof Restoration) ((Restoration) action).queued = true;
            if (ownership == null || !(action instanceof Restoration)) { callbacks.add(action); return; }
            var restoration = (Restoration) action;
            var objectRoot = cleanupSlot();
            try (var publication = objectRoot.prepare(objectRoot.current(), ownership.session().value(ObjectMemory.valueOf(restoration.object)))) { publication.commit(); }
            callbacks.add(restoration); callbacks.add(() -> releaseCleanup(objectRoot));
            if (restoration.payload != null) {
                var payloadRoot = cleanupSlot();
                var source = ownership.findFreshStage(ownership.session().value(restoration.payload));
                if (source == null) throw new IllegalArgumentException("Restoration payload lost its actual producer");
                try (var publication = source.prepareTransferTo(source.current(), payloadRoot, payloadRoot.current(), restoration.payload)) { publication.commit(); }
                callbacks.add(() -> releaseCleanup(payloadRoot));
            }
        }
        void drainCallbacks() throws Throwable { drainCallbacks(null); }
        void drainCallbacks(Throwable failure) throws Throwable {
            for (Action callback : callbacks) {
                if (callback instanceof Restoration && failure != null) {
                    ((Restoration) callback).object.doFinalize(); continue;
                }
                try { callback.run(); }
                catch (Throwable error) {
                    if (callback instanceof Restoration) ((Restoration) callback).object.doFinalize();
                    failure = ownership == null ? failure == null ? error : failure
                            : ownership.session().domain().graph().composeFailure(failure, error);
                }
            }
            callbacks.clear();
            if (failure != null) throw failure;
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
            if (type == 's') return new PhpByteStringMemory(string(';'));
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
        Slot register(Memory value) throws Throwable {
            Slot slot = new Slot(value); if (slots.isEmpty()) resultSlot = slot; slots.add(slot);
            if (nativeArena != null && !value.isArray() && !value.isObject()) slot.nativeProducer = nativeArena.temp(value);
            return slot;
        }
        Slot registerNativeObjectAlias(Slot target) throws Throwable {
            if (target.placement != null ? target.placement.owned == null : target.nativeProducer == null)
                throw new IllegalArgumentException("Native object alias lost its actual publisher");
            php.runtime.PhpOwnershipHeap.Publisher source;
            if (target.placement != null) {
                var address = target.placement.owned;
                var placement = address.current();
                var physical = address.resolve();
                if (physical == null) throw new InvalidData();
                source = physical.ownerPublisher();
                if (source.current().binding() != placement.binding())
                    throw new IllegalArgumentException("Object alias address differs from its actual owner");
            } else source = target.nativeProducer;
            var before = source.current();
            var binding = before.binding();
            if (binding == null) throw new InvalidData();
            if (binding.role() == php.runtime.PhpOwnershipValues.Role.CELL) {
                var arrays = php.runtime.PhpRuntimeOwnership.current(env).arrays();
                var certificate = arrays.certificate((ReferenceMemory) binding.rawValue());
                if (binding.node() != certificate.node()) throw new IllegalArgumentException("Object alias CELL differs from its publisher");
                binding = certificate.value().current().binding();
            }
            if (!(binding.rawValue() instanceof ObjectMemory)) throw new InvalidData();
            binding.requireCertifiedObject();
            var producer = nativeArena.empty();
            try {
                if (source.current() != before) throw new IllegalArgumentException("Object alias source changed before its VALUE copy");
                try (var publication = producer.prepare(producer.current(), binding)) { publication.commit(); }
                Slot slot = new Slot(producer.raw()); slot.nativeProducer = producer; slots.add(slot); return slot;
            } catch (Throwable failure) {
                try { producer.close(); }
                catch (Throwable cleanup) { failure = producer.domain().graph().composeFailure(failure, cleanup); }
                throw failure;
            }
        }
        Slot registerAllocation(Memory value) throws Throwable {
            Slot slot = register(value); slot.ownership = ownership;
            if (ownership != null) slot.producer = ownership.findFreshStage(ownership.session().value(value));
            if (nativeArena != null) {
                slot.nativeProducer = nativeAllocations.remove(value);
                if (slot.nativeProducer == null) throw new IllegalArgumentException("Parser allocation lost its exact native TEMP producer");
            }
            return slot;
        }
        ArrayMemory newArray() throws Throwable {
            if (nativeArena != null) {
                var producer = nativeArena.allocateArray();
                var array = producer.raw().toValue(ArrayMemory.class);
                assemblies.put(array, producer.assembly()); nativeAllocations.put(array, producer);
                return array;
            }
            if (ownership == null) return new ArrayMemory();
            try (var allocation = ownership.arrays().allocate(true)) {
                ArrayMemory array = allocation.array();
                ownership.stageAllocation(allocation, allocation.restoration());
                assemblies.put(array, ownership.consumeArrayRestoration(array));
                return array;
            }
        }
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

        Slot value() throws Throwable { return value(null); }
        Slot value(Placement destination) throws Throwable {
            int type = take();
            if (type == 'N') { expect(';'); return register(Memory.NULL); }
            expect(':');
            switch (type) {
                case 'R': case 'r':
                    int id = size(';');
                    if (id == 0 || id > slots.size()) throw new InvalidData();
                    Slot target = slots.get(id - 1);
                    if (type == 'R') {
                        if (target.placement != null && destination != null
                                && target.placement.entries.getStorageIdentity() == destination.entries.getStorageIdentity()
                                && target.placement.key.equals(destination.key)) throw new InvalidData();
                        target.promote(); return target;
                    }
                    if (nativeArena != null) return registerNativeObjectAlias(target);
                    Memory current = target.current();
                    if (!current.isObject()) throw new InvalidData();
                    return register(current);
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
                case 's': return register(new PhpByteStringMemory(string(';')));
                case 'a':
                    int count = size(':'); expect('{');
                    ArrayMemory array = newArray(); Slot slot = registerAllocation(array); frame(array, count, null); return slot;
                case 'E': {
                    int enumStart=pos-2;
                    int length=size(':');expect('"');
                    if(length>bytes.length-pos)throw new InvalidData();
                    String symbolic=new String(bytes,pos,length,StandardCharsets.UTF_8);pos+=length;
                    // Native E decoding reports the mismatched closing quote itself, without consuming it.
                    if(pos>=bytes.length || bytes[pos]!='"')throw new InvalidData();pos++;expect(';');int delimiter=symbolic.lastIndexOf(':');
                    if(delimiter<=0 || delimiter==symbolic.length()-1 || symbolic.indexOf('\0')>=0)throw new InvalidData();
                    String enumName=symbolic.substring(0,delimiter),caseName=symbolic.substring(delimiter+1);
                    ClassEntity declared=env.fetchClass(enumName,true,php.runtime.common.PhpLanguageProfile.PHP_8_5);
                    if(declared==null){env.error(trace,ErrorType.E_WARNING,"unserialize(): Class '%s' not found",enumName);pos=enumStart;throw new InvalidData();}
                    if(!declared.isEnum()){env.error(trace,ErrorType.E_WARNING,"unserialize(): Class '%s' is not an enum",enumName);throw new InvalidData();}
                    ConstantEntity constant=declared.findConstant(caseName);
                    if(constant==null || !constant.isEnumCase()){env.error(trace,ErrorType.E_WARNING,"unserialize(): Undefined constant %s::%s",enumName,caseName);throw new InvalidData();}
                    return register(constant.getValue(env));
                }
                case 'O': case 'C': return object(type == 'C');
                default: throw new InvalidData();
            }
        }

        Memory newNativeObject(ClassEntity clazz) throws Throwable {
            // Resolve only registered factories: class names and raw Java reachability are not allocation permits.
            if (clazz.findMethod("__unserialize") != null || clazz.methodMagicWakeup != null || clazz.methodDestruct != null)
                throw new IllegalArgumentException("Native OBJECT callbacks require their complete invocation protocol");
            var objects = php.runtime.PhpRuntimeOwnership.current(env).objects();
            var provider = objects.provider(clazz);
            if (!provider.properties().isEmpty())
                throw new IllegalArgumentException("Native declared defaults require their complete metadata producer plan");
            try (var allocation = objects.allocate(provider, env, Collections.emptyMap())) {
                var producer = nativeArena.stageAllocation(allocation, allocation.restoration());
                Memory value = producer.raw();
                assemblies.put(value.toValue(ObjectMemory.class).value.getProperties(), producer.assembly());
                nativeAllocations.put(value, producer);
                return value;
            }
        }
        Slot object(boolean custom) throws Throwable {
            if (nativeArena != null && custom)
                throw new IllegalArgumentException("Native custom unserialization requires its complete callback protocol");
            String name = new String(string(':'), StandardCharsets.UTF_8);
            if (name.indexOf('\0') >= 0) throw new InvalidData();
            boolean permitted = allowed(name);
            if (nativeArena != null && !permitted)
                throw new IllegalArgumentException("Native incomplete-class restoration requires its complete factory protocol");
            ClassEntity clazz = permitted ? env.fetchClass(name, nativeArena == null) : null;
            if (nativeArena != null && clazz == null)
                throw new IllegalArgumentException("Native class autoload requires its complete invocation protocol");
            boolean incomplete = clazz == null;
            if (incomplete) clazz = env.fetchClass("__PHP_Incomplete_Class", true);
            if(clazz.isEnum()) {
                if(custom)env.error(trace,ErrorType.E_WARNING,"Class %s has no unserializer",clazz.getName());
                env.exception(trace,BaseError.class,"Cannot instantiate enum %s",clazz.getName());
            }
            if ((clazz.getNativeClass() == php.runtime.lang.PhpWeakReference.class || clazz.getNativeClass() == php.runtime.lang.PhpFiber.class))
                env.exception(trace, BaseException.class, "Unserialization of '%s' is not allowed", name);
            MethodEntity modern = custom || incomplete ? null : clazz.findMethod("__unserialize");
            if (ownership != null && !custom && !incomplete) {
                if (modern != null) ownership.session().requireMethodCapability(modern);
                else if (clazz.methodMagicWakeup != null) ownership.session().requireMethodCapability(clazz.methodMagicWakeup);
            }
            Memory nativeObject = nativeArena == null ? null : newNativeObject(clazz);
            IObject object = nativeObject == null ? clazz.newObjectWithoutConstruct(env) : nativeObject.toValue(ObjectMemory.class).value;
            if (object == null) env.exception(trace, BaseException.class, "Unserialization of '%s' is not allowed", name);
            if (ownership != null) assemblies.put(object.getProperties(), ownership.consumeObjectRestoration(object));
            ArrayMemory payload = custom ? null : modern == null ? object.getProperties() : newArray();
            Restoration restoration = null;
            if (!custom && !incomplete && (modern != null || clazz.methodMagicWakeup != null)) {
                ClassEntity reflected = clazz;
                restoration = new Restoration(object, () -> {
                    if (modern != null) invoke(object, modern, payload);
                    else invoke(object, reflected.methodMagicWakeup);
                }, modern == null ? null : payload);
                restorations.add(restoration);
            }
            Slot slot = registerAllocation(nativeObject == null ? ObjectMemory.valueOf(object) : nativeObject);
            if (incomplete) object.getProperties().put("__PHP_Incomplete_Class_Name", StringMemory.valueOf(name));
            int count = size(':'); expect('{');
            if (custom) {
                if (count > bytes.length - pos) throw new InvalidData();
                Memory customPayload = new BinaryMemory(Arrays.copyOfRange(bytes, pos, pos + count)); pos += count; expect('}');
                if (!incomplete && object instanceof Serializable) invokeSerializableHook(env, trace, object, "unserialize", customPayload);
                else env.error(trace, ErrorType.E_WARNING, "Class %s has no unserializer", name);
                return slot;
            }
            frame(payload, count, restoration, modern == null, modern == null && !incomplete ? clazz : null);
            return slot;
        }
        void invoke(IObject object, MethodEntity method, Memory... args) throws Throwable {
            if (ownership != null) {
                var session = ownership.session();
                try (var roots = session.newCallRoots()) {
                    roots.stageReceiver(session.value(ObjectMemory.valueOf(object)));
                    for (Memory argument : args) roots.send(session.value(argument));
                    session.invokeMethod(method, object, trace, roots);
                }
                return;
            }
            env.pushCall(trace, object, method.getName(), args);
            try { method.invokeDynamic(object, env, trace, args); }
            finally { env.popCall(); }
        }
    }
}
