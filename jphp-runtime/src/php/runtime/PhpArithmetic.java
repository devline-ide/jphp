package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.invoke.cache.PropertyCallCache;
import php.runtime.lang.exception.BaseDivisionByZeroError;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.DoubleMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;

/** PHP 8 arithmetic shared by folding and JVM emission. */
public final class PhpArithmetic {
    private PhpArithmetic() {}

    public static boolean canFold(String operator, Memory left, Memory right) {
        left = left.toValue(); right = right.toValue();
        if (operator.equals("plus") && left.isArray() && right.isArray()) return true;
        if (!quietNumber(left) || !quietNumber(right)) return false;
        Memory a = left.isString() ? PhpNumericString.parseStrict(left.toString()) : left.toNumeric();
        Memory b = right.isString() ? PhpNumericString.parseStrict(right.toString()) : right.toNumeric();
        if (operator.equals("div") && b.toDouble() == 0) return false;
        if (operator.equals("mod")) {
            if (fractionalInteger(a) || fractionalInteger(b) || PhpNumericConversions.warnsInteger(a)
                    || PhpNumericConversions.warnsInteger(b) || PhpNumericConversions.floatToLong(b.toDouble()) == 0) return false;
        }
        return !operator.equals("pow") || a.toDouble() != 0 || !(b.toDouble() < 0);
    }

    public static boolean canFoldUnary(Memory value) { return quietNumber(value.toValue()); }
    private static boolean quietNumber(Memory value) {
        return value.isNumber() || value.isNull() || value.type == Memory.Type.BOOL
                || value.isString() && PhpNumericString.isNumeric(value.toString());
    }
    private static boolean fractionalInteger(Memory value) {
        if (value.type != Memory.Type.DOUBLE) return false;
        double real = value.toDouble();
        return Double.isNaN(real) || real >= -0x1p63 && real < 0x1p63 && real != (long) real;
    }

    public static Memory binary(Memory left, Memory right, String operator, Environment env, TraceInfo trace) {
        return binary(left, right, operator, env, trace, optimizationProfile(env, trace).getId());
    }

    /** Explicit profile for generated operations and constant folding, independent of caller state. */
    public static Memory binary(Memory left, Memory right, String operator, Environment env, TraceInfo trace, int optimizationProfileId) {
        boolean wrap = PhpOptimizationProfile.fromId(optimizationProfileId) == PhpOptimizationProfile.FAST_INT64_WRAP;
        left = left.toValue(); right = right.toValue();
        if (operator.equals("plus") && left.isArray() && right.isArray()) return left.plus(right);
        Memory a = number(left, operator, left, right, env, trace);
        if (operator.equals("mod")) {
            long dividend = integerOperand(a, left, env, trace);
            Memory b = number(right, operator, left, right, env, trace);
            long divisor = integerOperand(b, right, env, trace);
            if (divisor == 0) env.exception(trace, BaseDivisionByZeroError.class, "Modulo by zero");
            return LongMemory.valueOf(divisor == -1 ? 0 : dividend % divisor);
        }
        Memory b = number(right, operator, left, right, env, trace);
        boolean integers = a.type == Memory.Type.INT && b.type == Memory.Type.INT;
        switch (operator) {
            case "plus": return integers ? wrap ? LongMemory.valueOf(a.toLong() + b.toLong()) : OperatorUtils.plus(a.toLong(), b.toLong()) : DoubleMemory.valueOf(a.toDouble() + b.toDouble());
            case "minus": return integers ? wrap ? LongMemory.valueOf(a.toLong() - b.toLong()) : OperatorUtils.minus(a.toLong(), b.toLong()) : DoubleMemory.valueOf(a.toDouble() - b.toDouble());
            case "mul": return integers ? wrap ? LongMemory.valueOf(a.toLong() * b.toLong()) : OperatorUtils.mul(a.toLong(), b.toLong()) : DoubleMemory.valueOf(a.toDouble() * b.toDouble());
            case "div":
                if (b.toDouble() == 0) env.exception(trace, BaseDivisionByZeroError.class, "Division by zero");
                if (integers) {
                    long dividend = a.toLong(), divisor = b.toLong();
                    if (!(dividend == Long.MIN_VALUE && divisor == -1) && dividend % divisor == 0)
                        return LongMemory.valueOf(dividend / divisor);
                }
                return DoubleMemory.valueOf(a.toDouble() / b.toDouble());
            case "pow":
                if (a.toDouble() == 0 && b.toDouble() < 0)
                    env.error(trace, ErrorType.E_DEPRECATED, "Power of base 0 and negative exponent is deprecated");
                if (integers && b.toLong() >= 0) return integerPower(a.toLong(), b.toLong());
                return DoubleMemory.valueOf(power(a.toDouble(), b.toDouble()));
            default: throw new IllegalArgumentException("Unknown arithmetic operator: " + operator);
        }
    }

    public static Memory unary(Memory value, boolean negative, Environment env, TraceInfo trace) {
        return unary(value, negative, env, trace, optimizationProfile(env, trace).getId());
    }

    public static Memory unary(Memory value, boolean negative, Environment env, TraceInfo trace, int optimizationProfileId) {
        boolean wrap = PhpOptimizationProfile.fromId(optimizationProfileId) == PhpOptimizationProfile.FAST_INT64_WRAP;
        Memory number = number(value.toValue(), negative ? "negative" : "positive", value, null, env, trace);
        if (negative && wrap && number.type == Memory.Type.INT) return LongMemory.valueOf(-number.toLong());
        return negative ? number.negative() : number;
    }

    private static PhpOptimizationProfile optimizationProfile(Environment env, TraceInfo trace) {
        PhpPropertyWriteContext.Frame frame = PhpPropertyWriteContext.current(env);
        if (frame != null) return frame.optimizationProfile;
        return env == null ? PhpOptimizationProfile.COMPATIBLE : env.getModuleManager().getOptimizationProfile(trace);
    }

    /** PHP 8.5 value semantics; callers own the writable target and commit boundary. */
    public static Memory increment(Memory value, boolean decrement, Environment env, TraceInfo trace) {
        value = value.toValue();
        if (value.isUndefined()) value = Memory.NULL;
        if (value.type == Memory.Type.INT) {
            if (optimizationProfile(env, trace) == PhpOptimizationProfile.FAST_INT64_WRAP)
                return LongMemory.valueOf(decrement ? value.toLong() - 1 : value.toLong() + 1);
            return decrement ? OperatorUtils.minus(value.toLong(), 1) : OperatorUtils.plus(value.toLong(), 1);
        }
        if (value.type == Memory.Type.DOUBLE) return DoubleMemory.valueOf(value.toDouble() + (decrement ? -1 : 1));
        if (value.isNull() && !decrement) return Memory.CONST_INT_1;
        if (value.type == Memory.Type.BOOL || value.isNull()) {
            env.error(trace, ErrorType.E_WARNING,
                    "%s on type %s has no effect, this will change in the next major version of PHP",
                    decrement ? "Decrement" : "Increment", value.isNull() ? "null" : "bool");
            return value;
        }
        if (value.isString()) {
            String text = value instanceof php.runtime.memory.BinaryMemory || value instanceof php.runtime.memory.PhpByteStringMemory
                    ? value.toBinaryString() : value.toString();
            Memory numeric = PhpNumericString.parseStrict(text);
            if (numeric != null) return increment(numeric, decrement, env, trace);
            if (decrement) {
                env.error(trace, ErrorType.E_DEPRECATED, text.isEmpty()
                        ? "Decrement on empty string is deprecated as non-numeric"
                        : "Decrement on non-numeric string has no effect and is deprecated");
                return text.isEmpty() ? LongMemory.valueOf(-1) : value;
            }
            env.error(trace, ErrorType.E_DEPRECATED,
                    "Increment on non-numeric string is deprecated, use str_increment() instead");
            char[] chars = text.toCharArray();
            char prepend = '1'; boolean carry = chars.length == 0;
            for (int i = chars.length - 1; i >= 0; i--) {
                char ch = chars[i]; carry = false;
                if (ch >= 'a' && ch <= 'z') { prepend = 'a'; if (ch != 'z') { chars[i]++; break; } chars[i] = 'a'; carry = true; }
                else if (ch >= 'A' && ch <= 'Z') { prepend = 'A'; if (ch != 'Z') { chars[i]++; break; } chars[i] = 'A'; carry = true; }
                else if (ch >= '0' && ch <= '9') { prepend = '1'; if (ch != '9') { chars[i]++; break; } chars[i] = '0'; carry = true; }
                else break;
            }
            String result = (carry ? String.valueOf(prepend) : "") + new String(chars);
            if (value instanceof php.runtime.memory.PhpByteStringMemory) return php.runtime.memory.PhpByteStringMemory.fromBinaryString(result);
            if (value instanceof php.runtime.memory.BinaryMemory) {
                byte[] bytes = new byte[result.length()];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) result.charAt(i);
                return new php.runtime.memory.BinaryMemory(bytes);
            }
            return php.runtime.memory.StringMemory.valueOf(result);
        }
        env.exception(trace, BaseTypeError.class, "Cannot %s %s", decrement ? "decrement" : "increment", typeName(value));
        return Memory.NULL;
    }

    public static Memory incrementTarget(Memory target, boolean decrement, boolean post, Environment env, TraceInfo trace) {
        if (target instanceof ArrayTarget) target = ((ArrayTarget) target).reference();
        if (target instanceof php.runtime.memory.CharMemory)
            env.exception(trace, BaseError.class, "Cannot increment/decrement string offsets");
        if (target instanceof php.runtime.memory.ReferenceMemory)
            return ((php.runtime.memory.ReferenceMemory) target).incrementTarget(decrement, post, env, trace);
        Memory before = target.toValue().toImmutable();
        Memory result = increment(before, decrement, env, trace);
        target.assign(result);
        return post ? before : result;
    }

    /** Adapter entry point: do not export a PHP reference merely to capture an operator target. */
    public static Memory incrementReference(php.runtime.memory.ReferenceMemory target, boolean decrement, boolean post,
                                            Environment env, TraceInfo trace) {
        return incrementReference(target, decrement, post, env, trace, null);
    }

    public static Memory incrementReference(php.runtime.memory.ReferenceMemory target, boolean decrement, boolean post,
                                            Environment env, TraceInfo trace, java.util.function.Consumer<Memory> committed) {
        var cell = target.getReference();
        Memory before = cell.toValue().toImmutable();
        if (before instanceof php.runtime.memory.CharMemory)
            env.exception(trace, BaseError.class, "Cannot increment/decrement string offsets");
        if (optimizationProfile(env, trace) != PhpOptimizationProfile.FAST_INT64_WRAP)
            target.checkIncrementOverflow(before, decrement, env, trace);
        boolean diagnostic = before.type == Memory.Type.BOOL || decrement && before.isNull()
                || before.isString() && !PhpNumericString.isNumeric(before instanceof php.runtime.memory.BinaryMemory
                        ? before.toBinaryString() : before.toString());
        if (!diagnostic) {
            Memory result = increment(before, decrement, env, trace);
            result = target.assign(result);
            if (committed != null) committed.accept(result);
            return post ? before : result;
        }
        var captured = target.captureIncrementCell();
        Memory result;
        try {
            result = increment(before, decrement, env, trace);
        } catch (Throwable failure) {
            // Zend restores no-effect bool/null even when the warning handler throws.
            if (before.type == Memory.Type.BOOL || decrement && before.isNull()) {
                Memory restored = captured.restore(before);
                if (committed != null) committed.accept(restored);
            }
            throw failure;
        }
        try {
            result = before.type == Memory.Type.BOOL || decrement && before.isNull()
                    ? captured.restore(result) : captured.commit(result);
        } catch (Throwable failure) {
            Memory restored = captured.restore(before);
            if (committed != null) committed.accept(restored);
            throw failure;
        }
        if (committed != null) committed.accept(result);
        return post ? before : result;
    }

    public static Memory incrementProperty(Memory object, String field, Environment env, TraceInfo trace,
                                           PropertyCallCache cache, int index, boolean decrement, boolean post) throws Throwable {
        return ObjectInvokeHelper.incrementProperty(object, field, env, trace, cache, index, decrement, post);
    }

    public static Memory assign(Memory target, Memory right, String operator, Environment env, TraceInfo trace) {
        Memory result = binary(target, right, operator, env, trace);
        return target.assign(result).toImmutable();
    }

    public static Memory assign(Memory target, Memory right, String operator, Environment env, TraceInfo trace, int optimizationProfileId) {
        Memory result = binary(target, right, operator, env, trace, optimizationProfileId);
        return target.assign(result).toImmutable();
    }

    /** The generated addressed opcode owns its actual result, never a virtual lvalue wrapper. */
    public static Memory arrayIncrement(PhpRuntimeOwnership.Frame frame,int siteId,int rootLocalId,Memory actualRoot,
                                        String literalKey,boolean decrement,boolean post,Environment env,TraceInfo fetchTrace,TraceInfo operationTrace) throws Throwable {
        if (frame == null) return incrementTarget(arrayIncrementTarget(actualRoot,env,fetchTrace)
                .index(new php.runtime.memory.StringMemory(literalKey)),decrement,post,env,operationTrace);
        if (frame.authority().environment() != env) throw new IllegalArgumentException("ARRAY increment uses another Environment");
        var holder=frame.arrayIncrementHolder(siteId,rootLocalId,actualRoot,literalKey,decrement,post);
        var array=(php.runtime.memory.ArrayMemory)holder.raw();var origin=array.getRuntimeOrigin();
        if (origin == null || origin.authority().domain() != holder.domain() || origin.node() != holder.node())
            throw new IllegalArgumentException("ARRAY increment requires its complete actual physical backing protocol");
        origin.validate(array);
        Memory resolved=arrayIncrementTarget(actualRoot,env,fetchTrace).index(new php.runtime.memory.StringMemory(literalKey)).reference();
        Memory result=resolved instanceof php.runtime.memory.ReferenceMemory && ((php.runtime.memory.ReferenceMemory)resolved).getRuntimeHolder()!=null
                ? incrementRuntimeReference(frame,(php.runtime.memory.ReferenceMemory)resolved,decrement,post,env,operationTrace)
                : incrementTarget(resolved,decrement,post,env,operationTrace);
        try(var publication=frame.prepareEval(siteId,result,PhpRuntimeOwnership.Role.VALUE)) {
            publication.storeDirect();publication.commit();
        }
        return result;
    }

    /** The scalar result and warning continuation belong to this exact addressed CV opcode. */
    public static Memory localIncrement(PhpRuntimeOwnership.Frame frame,int siteId,int rootLocalId,Memory actualRoot,
                                        boolean decrement,boolean post,Environment env,TraceInfo trace)throws Throwable {
        if(frame==null)return incrementTarget(actualRoot,decrement,post,env,trace);
        if(frame.authority().environment()!=env)throw new IllegalArgumentException("Local increment uses another Environment");
        frame.validateLocalIncrement(siteId,rootLocalId,actualRoot,decrement,post);
        Memory result=incrementRuntimeReference(frame,(php.runtime.memory.ReferenceMemory)actualRoot,decrement,post,env,trace);
        try(var publication=frame.prepareEval(siteId,result,PhpRuntimeOwnership.Role.VALUE)){publication.storeDirect();publication.commit();}
        return result;
    }

    /** Only the actual addressed opcode can mint this scalar operator proof. */
    public static final class RuntimeIncrementTarget {
        private final PhpRuntimeOwnership.Frame frame;
        private final php.runtime.memory.ReferenceMemory target;
        private final PhpRuntimeOwnership.Holder holder;
        private final Memory before;
        private Memory result;
        private RuntimeIncrementTarget(PhpRuntimeOwnership.Frame frame,php.runtime.memory.ReferenceMemory target,Memory before) {
            this.frame=frame;this.target=target;this.holder=target.getRuntimeHolder();this.before=before;
            if(holder==null||!holder.matches(target)||holder.domain()!=frame.authority().domain())
                throw new IllegalArgumentException("Increment target lacks its actual physical publisher");
        }
        public void validateTarget(php.runtime.memory.ReferenceMemory actual) {
            if(actual!=target||actual.getRuntimeHolder()!=holder||!holder.matches(actual))
                throw new IllegalArgumentException("Increment proof belongs to another physical target");
        }
        public PhpRuntimeOwnership.Frame frame(){return frame;}
        public Memory scalar(){return before;}
        public void validateValue(Memory candidate,boolean restore) {
            if(candidate instanceof php.runtime.memory.ReferenceMemory||candidate.isArray()||candidate.isObject()
                    ||candidate!=(restore?before:result))
                throw new IllegalArgumentException("Increment publication is not its captured scalar or actual unary result");
        }
    }
    private static Memory incrementRuntimeReference(PhpRuntimeOwnership.Frame frame,php.runtime.memory.ReferenceMemory target,
            boolean decrement,boolean post,Environment env,TraceInfo trace)throws Throwable {
        Memory before=target.getReference().toValue().toImmutable();
        if (optimizationProfile(env, trace) != PhpOptimizationProfile.FAST_INT64_WRAP)
            target.checkIncrementOverflow(before,decrement,env,trace);
        boolean diagnostic=before.type==Memory.Type.BOOL||decrement&&before.isNull()
                ||before.isString()&&!PhpNumericString.isNumeric(before instanceof php.runtime.memory.BinaryMemory?before.toBinaryString():before.toString());
        if(!diagnostic)return incrementReference(target,decrement,post,env,trace);
        RuntimeIncrementTarget proof=new RuntimeIncrementTarget(frame,target,before);
        var captured=target.captureIncrementCell(proof);Throwable primary=null;
        try {
            Memory result;
            try {result=increment(before,decrement,env,trace);proof.result=result;}
            catch(Throwable failure) {
                if(before.type==Memory.Type.BOOL||decrement&&before.isNull()) {
                    try {captured.restore(before);}catch(Throwable cleanup){failure=frame.authority().domain().graph().composeFailure(failure,cleanup);}
                }
                throw failure;
            }
            try {result=before.type==Memory.Type.BOOL||decrement&&before.isNull()?captured.restore(before):captured.commit(result);}
            catch(Throwable failure) {
                if(!captured.hasRuntimeStore())try {captured.restore(before);}catch(Throwable cleanup){failure=frame.authority().domain().graph().composeFailure(failure,cleanup);}
                throw failure;
            }
            return post?before:result;
        }catch(Throwable failure){primary=failure;throw failure;}
        finally {
            try {captured.closeRuntime();}catch(Throwable cleanup){if(primary==null)throw cleanup;throw frame.authority().domain().graph().composeFailure(primary,cleanup);}
        }
    }

    public static ArrayTarget arrayTarget(Memory root, Environment env, TraceInfo trace) { return new ArrayTarget(root, env, trace, false); }
    public static ArrayTarget arrayIncrementTarget(Memory root, Environment env, TraceInfo trace) { return new ArrayTarget(root, env, trace, true); }
    public static final class ArrayTarget extends php.runtime.memory.ReferenceMemory {
        private final Memory root;
        private final Environment env;
        private final TraceInfo trace;
        private final boolean increment;
        private final java.util.List<Memory> keys = new java.util.ArrayList<>();
        private Memory reference;
        private ArrayTarget(Memory root, Environment env, TraceInfo trace, boolean increment) { this.root = root; this.env = env; this.trace = trace; this.increment = increment; }
        public ArrayTarget index(Memory key) { keys.add(key.toImmutable()); return this; }
        private Memory reference() {
            if (reference == null) {
                Memory current = root;
                for (int keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
                    Memory key = keys.get(keyIndex);
                    if (increment && current instanceof php.runtime.memory.ReferenceMemory)
                        current = ((php.runtime.memory.ReferenceMemory) current).incrementArrayCarrier(env, trace);
                    Memory container = current.toValue();
                    if (container.isNull() || container.type == Memory.Type.BOOL && !container.toBoolean()) {
                        if (!container.isNull()) env.error(trace, ErrorType.E_DEPRECATED, "Automatic conversion of false to array is deprecated");
                        current.assign(new php.runtime.memory.ArrayMemory());
                        container = current.toValue();
                    }
                    if (container.isString()) {
                        if (!increment) env.exception(trace, BaseError.class, "Cannot use assign-op operators with string offsets");
                        if (keyIndex != keys.size() - 1) env.exception(trace, BaseError.class, "Cannot use string offset as an array");
                        env.exception(trace, BaseError.class, "Cannot increment/decrement string offsets");
                    }
                    if (!container.isArray() && !container.isObject())
                        env.exception(trace, BaseError.class, "Cannot use a scalar value as an array");
                    if (container.isObject() && !(container.toValue(ObjectMemory.class).value instanceof php.runtime.lang.spl.ArrayAccess))
                        env.exception(trace, BaseError.class, "Cannot use object of type %s as array", typeName(container));
                    if (container.isArray()) {
                        var array = container.toValue(php.runtime.memory.ArrayMemory.class);
                        if (increment) array.checkCopied(); // Zend separates before converting/fetching the offset.
                        if (key.isArray() || key.isObject())
                            env.exception(trace, BaseTypeError.class, "Cannot access offset of type %s on array", typeName(key));
                        boolean conversionDiagnostic = increment && (key.isNull() || key.getRealType() == Memory.Type.DOUBLE);
                        if (conversionDiagnostic) {
                            try (var lease = array.pinWriteFetch()) {
                                if (key.isNull()) env.error(trace, ErrorType.E_DEPRECATED,
                                        "Using null as an array offset is deprecated, use an empty string instead");
                                else key = LongMemory.valueOf(integerOperand(key.toValue(), key, env, trace));
                                var continuation=lease.continuation(current, env);
                                if (continuation == null) return reference = new php.runtime.memory.ReferenceMemory(Memory.NULL);
                                array=continuation;
                            }
                        } else if (key.getRealType() == Memory.Type.DOUBLE) {
                            key = LongMemory.valueOf(integerOperand(key.toValue(), key, env, trace));
                        }
                        Object normalizedKey = php.runtime.memory.ArrayMemory.toKey(key);
                        boolean missing = array.getByScalar(normalizedKey) == null;
                        if (missing && (!increment || keyIndex == keys.size() - 1)) {
                            if (increment) {
                                try (var lease = array.pinWriteFetch()) {
                                    env.error(trace, ErrorType.E_WARNING, normalizedKey instanceof LongMemory
                                            ? "Undefined array key %s" : "Undefined array key \"%s\"", String.valueOf(normalizedKey));
                                    var continuation=lease.continuation(current, env);
                                    if (continuation == null) return reference = new php.runtime.memory.ReferenceMemory(Memory.NULL);
                                    array=continuation;
                                }
                            } else env.error(trace, ErrorType.E_WARNING, normalizedKey instanceof LongMemory
                                    ? "Undefined array key %s" : "Undefined array key \"%s\"", String.valueOf(normalizedKey));
                        }
                        current = array.refOfIndex(trace, key);
                    } else current = current.refOfIndex(trace, key);
                }
                reference = current;
            }
            return reference;
        }
        @Override public Memory getValue() { return reference().toValue(); }
        @Override public Memory toValue() { return getValue(); }
        @Override public <T extends Memory> T toValue(Class<T> clazz) { return clazz.cast(getValue()); }
        @Override public Memory assign(Memory value) { return reference().assign(value); }
    }

    public static Memory assignProperty(Memory object, Memory right, String field, Environment env, TraceInfo trace,
                                        PropertyCallCache cache, int index, String operator) throws Throwable {
        Memory receiver = object.toValue();
        if (receiver.isObject()) {
            var instance = receiver.toValue(ObjectMemory.class).value;
            return instance.getReflection().assignArithmeticProperty(env, trace, instance, field, right, operator, cache, index);
        }
        Memory left = ObjectInvokeHelper.getProperty(object, field, env, trace, cache, index);
        return ObjectInvokeHelper.assignProperty(object, binary(left, right, operator, env, trace),
                field, env, trace, cache, index);
    }

    private static Memory number(Memory value, String operator, Memory left, Memory right, Environment env, TraceInfo trace) {
        if (value.isNumber()) return value;
        if (value.isNull() || value.type == Memory.Type.BOOL) return LongMemory.valueOf(value.toLong());
        if (value.isString() && PhpNumericString.hasLeadingNumber(value.toString())) {
            if (!PhpNumericString.isNumeric(value.toString())) env.error(trace, ErrorType.E_WARNING, "A non-numeric value encountered");
            return PhpNumericString.parseLeading(value.toString());
        }
        String symbol = switch (operator) { case "plus" -> "+"; case "minus" -> "-"; case "mul" -> "*";
            case "div" -> "/"; case "mod" -> "%"; case "pow" -> "**"; default -> operator; };
        if (right == null) env.exception(trace, BaseTypeError.class, "Unsupported operand types: %s * int", typeName(left));
        else env.exception(trace, BaseTypeError.class, "Unsupported operand types: %s %s %s", typeName(left), symbol, typeName(right));
        return Memory.NULL;
    }

    private static String typeName(Memory value) {
        value = value.toValue();
        if (value.isResource()) return "resource";
        if (value.isObject()) return value.toValue(ObjectMemory.class).getReflection().getName();
        return switch (value.type) { case INT -> "int"; case DOUBLE -> "float"; case BOOL -> "bool"; case NULL -> "null";
            case ARRAY -> "array"; case STRING -> "string"; default -> value.type.toString(); };
    }

    private static long integerOperand(Memory value, Memory original, Environment env, TraceInfo trace) {
        long integer = PhpNumericConversions.toLong(value, env, trace);
        if (fractionalInteger(value)) env.error(trace, ErrorType.E_DEPRECATED,
                original.isString() ? "Implicit conversion from float-string \"%s\" to int loses precision"
                        : "Implicit conversion from float %s to int loses precision", original.toString());
        return integer;
    }

    private static Memory integerPower(long base, long exponent) {
        long result = 1;
        while (exponent != 0) {
            if ((exponent & 1) != 0) {
                long product = result * base;
                if (Math.multiplyHigh(result, base) != (product >> 63))
                    return DoubleMemory.valueOf((double) result * power(base, exponent));
                result = product;
            }
            exponent >>>= 1;
            if (exponent == 0) break;
            long square = base * base;
            if (Math.multiplyHigh(base, base) != (square >> 63))
                return DoubleMemory.valueOf((double) result * power(base, (double) exponent * 2));
            base = square;
        }
        return LongMemory.valueOf(result);
    }

    private static double power(double base, double exponent) {
        if (exponent == 0 || base == 1 || Math.abs(base) == 1 && Double.isInfinite(exponent)) return 1;
        return Math.pow(base, exponent);
    }
}
