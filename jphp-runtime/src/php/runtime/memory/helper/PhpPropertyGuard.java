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
    /** Typed-instance metadata only; no owner root, background callback or mutable Reference escape. */
    public static final class OwnerToken {
        private final java.lang.ref.PhantomReference<php.runtime.lang.IObject> owner;
        private OwnerToken(php.runtime.lang.IObject actual) {owner=new java.lang.ref.PhantomReference<>(java.util.Objects.requireNonNull(actual),null);}
        public boolean isExpired() {return owner.refersTo(null);}
        public boolean matches(php.runtime.lang.IObject actual) {return actual!=null && owner.refersTo(actual);}
        public void validateOwner(php.runtime.lang.IObject actual) {
            if (actual == null || !owner.refersTo(actual)) throw new IllegalArgumentException("Typed Source token has a different instance owner");
        }
    }
    public static OwnerToken instanceOwner(php.runtime.lang.IObject actual) {return new OwnerToken(actual);}
    public static final class Source {
        public final Environment environment;
        public final PropertyEntity property;
        public ReferenceMemory cell;
        private final OwnerToken owner;
        /** Static constraints and ephemeral magic-reference checks have no collectable instance owner. */
        public Source(Environment env, PropertyEntity property) {this(env,property,null);}
        public Source(Environment env, PropertyEntity property,OwnerToken owner) {this.environment=env;this.property=property;this.owner=property.isStatic()?null:owner;}
        public OwnerToken ownerToken() {return owner;}
        public boolean isExpired() {return owner != null && owner.isExpired();}
        /** A fresh declaration never retains the removed CELL or changes its instance lifetime. */
        public Source freshDeclaration() {return new Source(environment,property,owner);}
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
    /** Return the original identity when unchanged; never mutate a prepared Source snapshot in place. */
    public static List<Source> liveSources(List<Source> sources) {
        if (sources == null) return null;
        java.util.ArrayList<Source> live=null;
        for (int i=0;i<sources.size();i++) {
            Source source=sources.get(i);
            if (source.isExpired()) {
                if (live == null) {live=new java.util.ArrayList<>(sources.size());for (int j=0;j<i;j++) live.add(sources.get(j));}
            } else if (live != null) live.add(source);
        }
        return live == null ? sources : live.isEmpty() ? null : live;
    }
    private static String valueType(Memory value) {
        return value.getRealType() == Memory.Type.BOOL ? (value.toBoolean() ? "true" : "false") : PhpTypeDeclaration.valueType(value);
    }
    public static Memory write(List<Source> sources, Memory original, Source direct) {
        original=original == null ? Memory.NULL : original.toValue();
        sources=liveSources(sources);
        if (sources == null) return original;
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
        if (added.isExpired()) return value;
        sources=liveSources(sources);
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
        if (source.isExpired()) return;
        if (!source.type().allowsNull()) source.writer().exception(source.trace(), BaseError.class,
                "Cannot access uninitialized non-nullable property %s by reference", source.label());
    }
}
