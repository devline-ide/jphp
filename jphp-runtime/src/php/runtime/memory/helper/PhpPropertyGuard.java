package php.runtime.memory.helper;

import php.runtime.Memory;
import php.runtime.PhpPropertyWriteContext;
import php.runtime.PhpNumericConversions;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.util.List;

/** Constraints belong to the canonical cell, while each property owns a distinct source token. */
public final class PhpPropertyGuard {
    private PhpPropertyGuard() {}
    public static final class Source {
        public final Environment environment;
        public final PropertyEntity property;
        public ReferenceMemory cell;
        public Source(Environment env, PropertyEntity property) {this.environment=env;this.property=property;}
        public String label() {return property.getClazz().getName()+"::$"+property.getName();}
        public PhpTypeDeclaration type() {return property.getDeclaredType().bind(property.getClazz());}
        public Environment writer() {var context=PhpPropertyWriteContext.current(null);return context == null ? environment : context.environment;}
        public TraceInfo trace() {var context=PhpPropertyWriteContext.current(null);return context == null ? environment.trace() : context.trace;}
        public boolean strict() {var context=PhpPropertyWriteContext.current(null);return context != null && context.strict;}
        public Memory rawCoerce(Memory original) {
            var type=type();
            if (type.check(writer(), original, false, property.getClazz().getName())) return original;
            var converted=type.apply(writer(), original, false, strict());
            return converted;
        }
        public Memory coerce(Memory original) {return finishCoercion(original,rawCoerce(original));}
        public Memory finishCoercion(Memory original, Memory converted) {
            if (converted == null) return null;
            if (converted.getRealType() == Memory.Type.INT && original.getRealType() != Memory.Type.INT) {
                Memory numeric=original.isString() ? StringMemory.toNumericStrict(original.toString()) : original;
                if (numeric != null) PhpNumericConversions.warnIntegerPrecisionLoss(original, numeric, writer(), trace());
            }
            return PhpNumericConversions.checkedScalarCoercion(original, converted, writer(), trace());
        }
    }
    private static String valueType(Memory value) {
        return value.getRealType() == Memory.Type.BOOL ? (value.toBoolean() ? "true" : "false") : PhpTypeDeclaration.valueType(value);
    }
    public static Memory write(List<Source> sources, Memory original, Source direct) {
        original=original == null ? Memory.NULL : original.toValue();
        Memory result=null; Source first=null;
        for (Source source : sources) {
            Memory converted=source.rawCoerce(original);
            if (result != null && result != original && converted != null)
                converted=source.finishCoercion(original,converted);
            if (result != null && (converted != null && result != converted && !result.identical(converted)
                    || converted == null && !source.strict() && !original.isNull())) {
                source.writer().exception(source.trace(), BaseTypeError.class,
                        "Cannot assign %s to reference held by property %s of type %s and property %s of type %s, as this would result in an inconsistent type conversion",
                        valueType(original), first.label(), first.type().getErrorSignature(), source.label(), source.type().getErrorSignature());
                return Memory.NULL;
            }
            if (converted == null) {
                source.writer().exception(source.trace(), BaseTypeError.class,
                        "Cannot assign %s to %sproperty %s of type %s", valueType(original),
                        direct == source ? "" : "reference held by ", source.label(), source.type().getErrorSignature());
                return Memory.NULL;
            }
            if (result == null) converted=source.finishCoercion(original,converted);
            result=converted; if (first == null) first=source;
        }
        return result == null ? original : result;
    }
    public static Memory bind(Source added, ReferenceMemory target, List<Source> sources) {
        Memory value=target.toValue();
        if (sources != null && !sources.isEmpty()) {
            if (!added.type().check(added.writer(), value, false, added.property.getClazz().getName())) {
                if (added.type().apply(added.writer(), value, false, added.strict()) == null)
                    return write(List.of(added),value,added);
                Source held=sources.get(0);
                added.writer().exception(added.trace(), BaseTypeError.class,
                        "Reference with value of type %s held by property %s of type %s is not compatible with property %s of type %s",
                        valueType(value), held.label(), held.type().getErrorSignature(), added.label(), added.type().getErrorSignature());
            }
            return value;
        }
        return write(List.of(added), value, added);
    }
    public static void initializeReference(Source source) {
        if (!source.type().allowsNull()) source.writer().exception(source.trace(), BaseError.class,
                "Cannot access uninitialized non-nullable property %s by reference", source.label());
    }
}
