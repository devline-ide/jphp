package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseArgumentCountError;
import php.runtime.lang.exception.BaseError;
import php.runtime.reflection.ParameterEntity;
import java.util.*;

/** One resolved call's destinations. It never evaluates expressions or owns PHP values. */
public final class PhpArgumentBinder {
    public static final class Signature {
        private final Object target;
        private final ParameterEntity[] parameters;
        private final boolean completeNames, magic;
        private final int required;
        public Signature(Object target, ParameterEntity[] parameters, boolean completeNames, boolean magic) {
            this.target = Objects.requireNonNull(target, "resolved target");
            this.parameters = parameters == null ? new ParameterEntity[0] : parameters;
            this.completeNames = completeNames;
            this.magic = magic;
            if (magic && this.parameters.length != 0) throw new IllegalArgumentException("Magic arguments have no fixed signature");
            var names = new HashSet<String>();
            int mandatory = 0;
            for (int i = 0; i < this.parameters.length; i++) {
                var parameter = Objects.requireNonNull(this.parameters[i], "parameter");
                if (!parameter.isOptional() && !parameter.isVariadic()) mandatory = i + 1;
                if (parameter.isVariadic() && i != this.parameters.length - 1)
                    throw new IllegalArgumentException("Variadic parameter must be last");
                if (completeNames && (parameter.getName() == null || !names.add(parameter.getName())))
                    throw new IllegalArgumentException("Missing or duplicate PHP parameter name");
            }
            required = mandatory;
        }
        public Object target() {return target;}
        public ParameterEntity[] parameters() {return parameters;}
        public boolean completeNames() {return completeNames;}
        public boolean magic() {return magic;}
        public int fixedCount() {return parameters.length - (variadic() == null ? 0 : 1);}
        // PHP makes an earlier default implicitly required when a mandatory parameter follows it.
        public boolean isRequired(int index) {return index >= 0 && index < required;}
        public ParameterEntity variadic() {
            return parameters.length != 0 && parameters[parameters.length - 1].isVariadic()
                    ? parameters[parameters.length - 1] : null;
        }
    }

    private final Object owner;
    private final Thread thread = Thread.currentThread();
    private final Environment env;
    private final TraceInfo trace;
    private final Signature signature;
    private final String callName;
    private final Memory[] fixed;
    private final boolean[] supplied;
    private final List<Memory> positional = new ArrayList<>();
    private final LinkedHashMap<String, Memory> named = new LinkedHashMap<>();
    private int cursor, highest = -1, pendingIndex;
    private String pendingName, pendingError;
    private boolean pending, namedSeen, unpackSeen, explicitNamedSeen, finished;

    public PhpArgumentBinder(Object owner, Environment env, TraceInfo trace, Signature signature, String callName) {
        this.owner = Objects.requireNonNull(owner);
        this.env = Objects.requireNonNull(env);
        this.trace = trace;
        this.signature = Objects.requireNonNull(signature);
        this.callName = callName;
        fixed = new Memory[signature.fixedCount()];
        supplied = new boolean[fixed.length];
    }
    private void open() {
        if (thread != Thread.currentThread()) throw new IllegalStateException("Foreign argument-binding thread");
        if (finished) throw new IllegalStateException("Argument binding has finished");
    }
    private void destination() {open();if (!pending) throw new IllegalStateException("No prepared SEND destination");}
    public void prepareNamed(String name) {prepare(Objects.requireNonNull(name, "PHP argument name"), false);explicitNamedSeen = true;}
    public void preparePositional() {prepare(null, false);}
    public void beginUnpack() {
        open();
        if (pending) throw new IllegalStateException("Unfinished SEND before unpack");
        if (explicitNamedSeen) env.exception(trace, BaseError.class, "Cannot use argument unpacking after named arguments");
        unpackSeen = true;
    }
    public void beginKeyed() {
        open();if (pending) throw new IllegalStateException("Unfinished SEND before keyed arguments");
    }
    public void prepareKeyed(Memory key) {
        open();
        if (key.isString()) prepare(key.toString(), false);
        else if (key.getRealType() == Memory.Type.INT) prepare(null, false);
        else throw new IllegalArgumentException("A PHP argument array key must be int|string");
    }
    public void prepareUnpacked(Memory key) {
        open();
        if (key.isString()) prepare(key.toString(), true);
        else if (key.getRealType() == Memory.Type.INT) prepare(null, true);
        else env.exception(trace, BaseError.class, "Keys must be of type int|string during argument unpacking");
    }
    private void prepare(String name, boolean unpacked) {
        open();
        if (pending) throw new IllegalStateException("Previous SEND has not committed");
        pending = true;pendingName = name;pendingError = null;pendingIndex = -1;
        if (name != null) {
            namedSeen = true;
            if (!signature.completeNames()) {
                pendingError = "Named arguments are unavailable for this target: PHP signature metadata is incomplete";
                return;
            }
            for (int i = 0; i < fixed.length; i++) {
                if (name.equals(signature.parameters()[i].getName())) {
                    pendingIndex = i;
                    if (supplied[i]) pendingError = "Named parameter $" + name + " overwrites previous argument";
                    return;
                }
            }
            if (named.containsKey(name)) pendingError = "Named parameter $" + name + " overwrites previous argument";
            else if (signature.variadic() == null && !signature.magic()) pendingError = "Unknown named parameter $" + name;
        } else {
            pendingIndex = cursor;
            if (namedSeen) pendingError = unpacked ? "Cannot use positional argument after named argument during unpacking"
                    : "Cannot use positional argument after named argument";
            else if (unpackSeen && !unpacked) pendingError = "Cannot use positional argument after argument unpacking";
        }
    }
    /** Pending lookup failure suppresses REF fetch and is raised after the current expression. */
    public ParameterEntity parameter() {
        destination();
        if (pendingError != null) return null;
        return pendingIndex >= 0 && pendingIndex < fixed.length ? signature.parameters()[pendingIndex] : signature.variadic();
    }
    public boolean hasPendingDestination() {open();return pending;}
    public boolean hasPendingError() {destination();return pendingError != null;}
    public Signature signature() {return signature;}
    public int parameterIndex() {destination();return pendingIndex >= 0 ? pendingIndex : fixed.length;}
    public void validateSend() {
        destination();
        if (pendingError != null) env.exception(trace, BaseError.class, pendingError);
    }
    public void send(Memory value) {
        validateSend();Objects.requireNonNull(value, "PHP NULL is Memory.NULL, not a Java hole");
        if (pendingName != null && pendingIndex < 0) named.put(pendingName, value);
        else if (pendingIndex < fixed.length) {
            fixed[pendingIndex] = value;supplied[pendingIndex] = true;highest = Math.max(highest, pendingIndex);
        } else positional.add(value);
        if (pendingName == null) cursor++;
        pending = false;pendingName = null;pendingError = null;
    }
    void release() {
        finished = true;Arrays.fill(fixed, null);positional.clear();named.clear();
        pending = false;pendingName = null;pendingError = null;
    }
    public BoundArguments finish() {
        open();if (pending) throw new IllegalStateException("Current argument expression has not sent its value");
        if (namedSeen && !signature.magic()) {
            for (int i = 0; i < fixed.length; i++) {
                if (!supplied[i] && signature.isRequired(i))
                    env.exception(trace, BaseArgumentCountError.class, "%s(): Argument #%d ($%s) not passed",
                            callName, i + 1, signature.parameters()[i].getName());
            }
        }
        int prefix = positional.isEmpty() ? highest + 1 : fixed.length;
        Memory[] vector = Arrays.copyOf(fixed, prefix + positional.size());
        for (int i = 0; i < positional.size(); i++) vector[prefix + i] = positional.get(i);
        finished = true;
        return new BoundArguments(owner, env, signature, vector, supplied.clone(), new LinkedHashMap<>(named));
    }
}
