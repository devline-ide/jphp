package org.develnext.jphp.zend.ext.json;

import org.develnext.jphp.json.JsonSerializable;
import php.runtime.Memory;
import php.runtime.PhpNumericString;
import php.runtime.PhpPropertyHookAccess;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseEnum;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Modern PHP JSON wire encoding; iterative traversal, exact byte input, no intermediate JSON tree. */
final class Php85JsonEncoder {
    private static final String CONTEXT = Php85JsonEncoder.class.getName() + "#recursion";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Stop STOP = new Stop();
    private static final class Stop extends RuntimeException {
        private Stop() { super(null, null, false, false); }
    }
    private static final class Context {
        final IdentityHashMap<Object, Boolean> containers = new IdentityHashMap<>();
        final IdentityHashMap<IObject, Boolean> serializing = new IdentityHashMap<>();
    }
    private final Environment env;
    private final TraceInfo trace;
    private final int flags, maximumDepth;
    private final boolean partial, pretty;
    private final StringBuilder output = new StringBuilder();
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private Context context;
    private boolean ownsContext;
    private Memory pending;
    private int depth, error, unitLength;

    Php85JsonEncoder(Environment env, TraceInfo trace, int flags, int depth) {
        this.env = env; this.trace = trace; this.flags = flags; maximumDepth = depth;
        partial = (flags & JsonConstants.JSON_PARTIAL_OUTPUT_ON_ERROR) != 0;
        pretty = (flags & JsonConstants.JSON_PRETTY_PRINT) != 0;
    }
    int error() { return error; }
    Memory encode(Memory value) throws Throwable {
        pending = value;
        Throwable primary = null;
        try {
            while (pending != null || !frames.isEmpty()) {
                if (pending != null) {
                    Memory next = pending; pending = null; value(next);
                } else frames.peek().next();
            }
        } catch (Stop finished) {
            if (finished != STOP) throw finished;
        } catch (Throwable failure) {
            primary = failure; throw failure;
        } finally {
            Throwable cleanup = null;
            while (!frames.isEmpty()) {
                try { frames.pop().release(); }
                catch (Throwable failure) { if (cleanup == null) cleanup = failure; else if (cleanup != failure) cleanup.addSuppressed(failure); }
            }
            if (ownsContext) {
                try {
                    if (env.getUserValue(CONTEXT, Object.class) != context || !context.containers.isEmpty() || !context.serializing.isEmpty())
                        throw new IllegalStateException("JSON recursion scope was not restored");
                    env.setUserValue(CONTEXT, null);
                } catch (Throwable failure) { if (cleanup == null) cleanup = failure; else if (cleanup != failure) cleanup.addSuppressed(failure); }
            }
            pending = null;
            if (cleanup != null) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
        return StringMemory.valueOf(output.toString());
    }
    private Context context() {
        if (context == null) {
            Object current = env.getUserValue(CONTEXT, Object.class);
            if (current != null && !(current instanceof Context)) throw new IllegalStateException("Invalid JSON recursion scope");
            if (current == null) { context = new Context(); ownsContext = true; env.setUserValue(CONTEXT, context); }
            else context = (Context) current;
        }
        return context;
    }
    private void failure(int code, String replacement) {
        error = code;
        if (partial) output.append(replacement);
        else throw STOP;
    }
    private void value(Memory raw) throws Throwable {
        Memory value = raw.toValue();
        if (value.isResource()) { failure(JsonConstants.JSON_ERROR_UNSUPPORTED_TYPE, "null"); return; }
        switch (value.getRealType()) {
            case NULL: output.append("null"); return;
            case BOOL: output.append(value.toBoolean() ? "true" : "false"); return;
            case INT: output.append(value.toLong()); return;
            case DOUBLE:
                if (!Double.isFinite(value.toDouble())) { error = JsonConstants.JSON_ERROR_INF_OR_NAN; output.append('0'); }
                else number(value.toDouble());
                return;
            case STRING: string(value, false); return;
            case ARRAY: array(value.toValue(ArrayMemory.class)); return;
            case OBJECT: {
                IObject object = value.toValue(ObjectMemory.class).value;
                if (object instanceof JsonSerializable serializable) {
                    if (context().serializing.containsKey(object)) { failure(JsonConstants.JSON_ERROR_RECURSION, "null"); return; }
                    context.serializing.put(object, Boolean.TRUE);
                    Guard guard = new Guard(object); frames.push(guard);
                    env.pushCall(object, "jsonSerialize");
                    Memory result;
                    try { result = serializable.jsonSerialize(env).toValue(); }
                    finally { env.popCall(); }
                    if (result.isObject() && result.toValue(ObjectMemory.class).value == object) {
                        frames.pop(); guard.release(); object(object);
                    } else pending = result;
                } else if (object instanceof BaseEnum enumeration) {
                    if (object.getReflection().getEnumBackingType() == null) {
                        error = JsonConstants.JSON_ERROR_NON_BACKED_ENUM; output.append('0'); if (!partial) throw STOP;
                    } else pending = enumeration.enumBackingValue();
                } else object(object);
                return;
            }
            default: failure(JsonConstants.JSON_ERROR_UNSUPPORTED_TYPE, "null");
        }
    }
    private abstract class Frame {
        abstract void next() throws Throwable;
        abstract void release() throws Throwable;
    }
    private final class Guard extends Frame {
        private final IObject object;
        Guard(IObject object) { this.object = object; }
        void next() { frames.pop(); release(); }
        void release() { context.serializing.remove(object); }
    }
    private void array(ArrayMemory source) {
        Object identity = source.getStorageIdentity();
        if (context().containers.containsKey(identity)) { failure(JsonConstants.JSON_ERROR_RECURSION, "null"); return; }
        ArrayMemory copy = source.toImmutableForNativeRead();
        boolean asObject = (flags & JsonConstants.JSON_FORCE_OBJECT) != 0;
        if (!asObject) {
            long expected = 0; ForeachIterator scan = copy.foreachIterator(false, false);
            while (scan.next()) {
                Memory key = scan.getMemoryKey();
                if (key.getRealType() != Memory.Type.INT || key.toLong() != expected++) { asObject = true; break; }
            }
        }
        context.containers.put(identity, Boolean.TRUE);
        frames.push(new Container(identity, copy, null, asObject));
        output.append(asObject ? '{' : '['); ++depth;
    }
    private void object(IObject object) {
        object = php.runtime.PhpLazyObjects.observeUnchecked(env,object,php.runtime.PhpLazyObjects.Observation.PROPERTIES);
        if (context().containers.containsKey(object)) { failure(JsonConstants.JSON_ERROR_RECURSION, "null"); return; }
        context.containers.put(object, Boolean.TRUE);
        Container frame = new Container(object, null, null, true); frames.push(frame);
        // Install the frame before obtaining values so callback/cleanup paths always retain the real guard.
        frame.properties = new Properties(object); frame.properties.capture();
        output.append('{'); ++depth;
    }
    private final class Container extends Frame {
        final Object identity;
        final ArrayMemory snapshot;
        final ForeachIterator iterator;
        final boolean asObject;
        Properties properties;
        boolean emitted, released;
        Container(Object identity, ArrayMemory snapshot, Properties properties, boolean asObject) {
            this.identity = identity; this.snapshot = snapshot; this.properties = properties; this.asObject = asObject;
            iterator = snapshot == null ? null : snapshot.foreachIterator(false, false);
        }
        void next() throws Throwable {
            boolean next = iterator == null ? properties.next() : iterator.next();
            if (!next) {
                frames.pop(); int level = depth; --depth; release();
                if (level > maximumDepth) { error = JsonConstants.JSON_ERROR_DEPTH; if (!partial) throw STOP; }
                if (pretty && emitted) { output.append('\n'); indent(depth); }
                output.append(asObject ? '}' : ']'); return;
            }
            Memory key = iterator == null ? properties.key : iterator.getMemoryKey();
            Memory value = iterator == null ? properties.value : iterator.getValue();
            if (emitted) output.append(','); emitted = true;
            if (pretty) { output.append('\n'); indent(depth); }
            if (asObject) { string(key, true); output.append(':'); if (pretty) output.append(' '); }
            pending = value;
        }
        void release() throws Throwable {
            if (released) return; released = true; context.containers.remove(identity);
            try { if (properties != null) properties.release(); }
            finally { if (snapshot != null) snapshot.unset(); }
        }
    }
    private final class Property {
        final Memory key;
        final Object scalar;
        final PropertyEntity declared;
        final Memory captured;
        Property(Memory key, Object scalar, PropertyEntity declared, Memory captured) {
            this.key = key; this.scalar = scalar; this.declared = declared; this.captured = captured;
        }
    }
    /** Native dynamic properties are retained values; declared fields and hook getters remain live reads. */
    private final class Properties {
        final IObject object;
        final List<Property> positions = new ArrayList<>();
        final List<ArrayMemory> retained = new ArrayList<>();
        int index;
        Memory key, value;
        Properties(IObject object) { this.object = object; }
        private void capturePhysical() {
            ForeachIterator raw = object.getProperties().foreachIterator(false, false);
            while (raw.next()) {
                Memory name = raw.getMemoryKey(); byte[] bytes = name.getBinaryBytes(StandardCharsets.UTF_8);
                if (bytes.length != 0 && bytes[0] == 0) continue;
                Object scalar = raw.getKey();
                PropertyEntity declared = object.getReflection().findProperty(name.toString());
                if (declared != null && !declared.isStatic() && declared.isPublic()) {
                    positions.add(new Property(name, scalar, declared, null));
                } else {
                    Memory captured = raw.getValue();
                    if (!captured.isReference()) {
                        if (captured.isArray()) {
                            ArrayMemory copy = captured.toValue(ArrayMemory.class).toImmutableForNativeRead(); retained.add(copy); captured = copy;
                        } else captured = captured.toImmutable();
                    }
                    positions.add(new Property(name, scalar, null, captured));
                }
            }
        }
        void capture() {
            if(object instanceof php.runtime.lang.support.IPhpNativeDateView date) {
                var raw=date.phpDateExport(env).foreachIterator(false,false);
                while(raw.next()) {
                    Memory name=raw.getMemoryKey();if(name.toString().indexOf('\0')>=0||raw.getValue().isUndefined())continue;
                    Memory captured=raw.getValue();
                    if(!captured.isReference()) {
                        if(captured.isArray()){ArrayMemory copy=captured.toValue(ArrayMemory.class).toImmutableForNativeRead();retained.add(copy);captured=copy;}
                        else captured=captured.toImmutable();
                    }
                    positions.add(new Property(name,raw.getKey(),null,captured));
                }
                return;
            }
            if (object instanceof php.runtime.lang.spl.iterator.ArrayObject) {
                ArrayMemory storage = ((php.runtime.lang.spl.iterator.ArrayObject)object).castArray(env).toValue(ArrayMemory.class);
                ForeachIterator raw = storage.foreachIterator(false, false);
                while (raw.next()) {
                    Memory name=raw.getMemoryKey();
                    if (name.toString().indexOf('\0')>=0 || raw.getValue().isUndefined()) continue;
                    Memory captured=raw.getValue();
                    if (!captured.isReference()) {
                        if (captured.isArray()) { ArrayMemory copy=captured.toValue(ArrayMemory.class).toImmutableForNativeRead();retained.add(copy);captured=copy; }
                        else captured=captured.toImmutable();
                    }
                    positions.add(new Property(name,raw.getKey(),null,captured));
                }
                return;
            }
            boolean hooked = false;
            for (ClassEntity type = object.getReflection(); type != null && !hooked; type = type.getParent()) {
                for (PropertyEntity property : type.getProperties()) {
                    if (property.getPhpHooks() != null) { hooked = true; break; }
                }
            }
            if (!hooked) { capturePhysical(); return; }
            LinkedHashMap<Object, Property> ordered = new LinkedHashMap<>();
            ArrayDeque<ClassEntity> lineage = new ArrayDeque<>();
            for (ClassEntity type = object.getReflection(); type != null; type = type.getParent()) lineage.push(type);
            while (!lineage.isEmpty()) {
                ClassEntity type = lineage.pop();
                for (PropertyEntity entry : type.getProperties()) {
                    if (entry.isStatic() || entry.getClazz() != type || entry.isPrivate()) continue;
                    PropertyEntity selected = object.getReflection().findProperty(entry.getName());
                    if (selected != null && !selected.isStatic() && selected.isPublic()) {
                        Object scalar = selected.getSpecificName();
                        ordered.putIfAbsent(scalar, new Property(StringMemory.valueOf(selected.getName()), scalar, selected, null));
                    }
                }
            }
            positions.addAll(ordered.values());
            ForeachIterator raw = object.getProperties().foreachIterator(false, false);
            while (raw.next()) {
                Memory name = raw.getMemoryKey(); byte[] bytes = name.getBinaryBytes(StandardCharsets.UTF_8);
                if (bytes.length != 0 && bytes[0] == 0) continue;
                Object scalar = raw.getKey();
                if (!ordered.containsKey(scalar)) {
                    Memory captured = raw.getValue();
                    if (!captured.isReference()) {
                        if (captured.isArray()) {
                            ArrayMemory copy = captured.toValue(ArrayMemory.class).toImmutableForNativeRead(); retained.add(copy); captured = copy;
                        } else captured = captured.toImmutable();
                    }
                    positions.add(new Property(name, scalar, null, captured));
                }
            }
        }
        boolean next() throws Throwable {
            while (index < positions.size()) {
                Property property = positions.get(index++); key = property.key;
                if (property.declared == null) value = property.captured;
                else {
                    var hooks = property.declared.getPhpHooks();
                    if (hooks != null && hooks.isVirtual() && hooks.get() == null) continue;
                    value = PhpPropertyHookAccess.read(property.declared, env, trace, object, false, null, null);
                    if (value == null) {
                        ReferenceMemory current = object.getProperties().getByScalar(property.scalar);
                        if (current == null) continue; value = current.getValue();
                    }
                }
                if (!value.isUndefined()) return true;
            }
            return false;
        }
        void release() throws Throwable {
            Throwable primary = null;
            for (ArrayMemory copy : retained) {
                try { copy.unset(); }
                catch (Throwable failure) { if (primary == null) primary = failure; else if (primary != failure) primary.addSuppressed(failure); }
            }
            retained.clear(); if (primary != null) throw primary;
        }
    }
    private void indent(int level) { for (int i = 0; i < level; ++i) output.append("    "); }
    private void number(double value) {
        if (value == 0) {
            if (Double.doubleToRawLongBits(value) < 0) output.append('-');
            output.append((flags & JsonConstants.JSON_PRESERVE_ZERO_FRACTION) != 0 ? "0.0" : "0"); return;
        }
        int precision = env.getConfigValue("serialize_precision", LongMemory.valueOf(-1)).toInteger();
        BigDecimal number = (precision < 0 ? BigDecimal.valueOf(value)
                : new BigDecimal(value).round(new MathContext(Math.max(1, precision), RoundingMode.HALF_EVEN))).stripTrailingZeros();
        int exponent = number.precision() - number.scale() - 1;
        if (exponent >= -4 && exponent < (precision < 0 ? 17 : Math.max(1, precision))) {
            String text = number.toPlainString(); output.append(text);
            if ((flags & JsonConstants.JSON_PRESERVE_ZERO_FRACTION) != 0 && text.indexOf('.') < 0) output.append(".0");
        } else {
            String mantissa = number.movePointLeft(exponent).toPlainString(); output.append(mantissa);
            if (mantissa.indexOf('.') < 0) output.append(".0");
            output.append('e'); if (exponent >= 0) output.append('+'); output.append(exponent);
        }
    }
    private void string(Memory value, boolean key) {
        if (value.getClass() == StringMemory.class && (key || (flags & JsonConstants.JSON_NUMERIC_CHECK) == 0)) {
            String text = value.toString(); boolean safe = true;
            for (int i = 0; i < text.length(); ++i) {
                char c = text.charAt(i);
                if (c < 32 || c > 126 || c == '"' || c == '\\' || c == '/' || c == '<' || c == '>' || c == '&' || c == '\'') {
                    safe = false; break;
                }
            }
            if (safe) { output.append('"').append(text).append('"'); return; }
        }
        byte[] bytes = value.getBinaryBytes(StandardCharsets.UTF_8);
        if (!key && (flags & JsonConstants.JSON_NUMERIC_CHECK) != 0) {
            Memory numeric = PhpNumericString.parseStrict(new String(bytes, StandardCharsets.ISO_8859_1));
            if (numeric != null && (numeric.getRealType() == Memory.Type.INT || Double.isFinite(numeric.toDouble()))) {
                if (numeric.getRealType() == Memory.Type.INT) output.append(numeric.toLong()); else number(numeric.toDouble()); return;
            }
        }
        int checkpoint = output.length(); output.append('"');
        for (int i = 0; i < bytes.length;) {
            int c = bytes[i] & 255;
            if (c >= 128) {
                int code = codePoint(bytes, i); i += unitLength;
                if (code < 0) {
                    if ((flags & JsonConstants.JSON_INVALID_UTF8_IGNORE) != 0) continue;
                    if ((flags & JsonConstants.JSON_INVALID_UTF8_SUBSTITUTE) != 0) code = 0xfffd;
                    else {
                        output.setLength(checkpoint); error = JsonConstants.JSON_ERROR_UTF8;
                        if (!partial) throw STOP;
                        output.append(key ? "\"\"" : "null"); return;
                    }
                }
                if ((flags & JsonConstants.JSON_UNESCAPED_UNICODE) != 0
                        && ((flags & JsonConstants.JSON_UNESCAPED_LINE_TERMINATORS) != 0 || code < 0x2028 || code > 0x2029)) output.appendCodePoint(code);
                else if (code > 0xffff) { int point = code - 0x10000; unicode(0xd800 | point >>> 10); unicode(0xdc00 | point & 1023); }
                else unicode(code);
                continue;
            }
            ++i;
            switch (c) {
                case '"': output.append((flags & JsonConstants.JSON_HEX_QUOT) != 0 ? "\\u0022" : "\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '/': output.append((flags & JsonConstants.JSON_UNESCAPED_SLASHES) != 0 ? "/" : "\\/"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                case '<': output.append((flags & JsonConstants.JSON_HEX_TAG) != 0 ? "\\u003C" : "<"); break;
                case '>': output.append((flags & JsonConstants.JSON_HEX_TAG) != 0 ? "\\u003E" : ">"); break;
                case '&': output.append((flags & JsonConstants.JSON_HEX_AMP) != 0 ? "\\u0026" : "&"); break;
                case '\'': output.append((flags & JsonConstants.JSON_HEX_APOS) != 0 ? "\\u0027" : "'"); break;
                default: if (c < 32) unicode(c); else output.append((char)c);
            }
        }
        output.append('"');
    }
    private void unicode(int c) {
        output.append("\\u").append(HEX[c >>> 12 & 15]).append(HEX[c >>> 8 & 15]).append(HEX[c >>> 4 & 15]).append(HEX[c & 15]);
    }
    /** Consume the valid continuation prefix, then reject overlong/surrogate codepoints as one unit. */
    private int codePoint(byte[] bytes, int offset) {
        int first = bytes[offset] & 255, size = first >= 0xc2 && first <= 0xdf ? 2 : first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
        unitLength = 1; if (size == 0) return -1;
        int code = first & (127 >> size);
        while (unitLength < size && offset + unitLength < bytes.length) {
            int c = bytes[offset + unitLength] & 255; if ((c & 0xc0) != 0x80) break;
            code = code << 6 | c & 63; ++unitLength;
        }
        if (unitLength != size || code < (size == 2 ? 128 : size == 3 ? 2048 : 65536) || code > 0x10ffff || code >= 0xd800 && code <= 0xdfff) return -1;
        return code;
    }
}
