package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.lang.spl.ArrayAccess;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.BinaryMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.ArrayMemory;
import php.runtime.exceptions.support.ErrorType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

/** Evaluates an isset fetch once and delays write-context lookup until after the RHS. */
public final class NullCoalesceAssignment {
    private final Memory root;
    private final Environment env;
    private final TraceInfo trace;
    private final String className, staticProperty;
    private final boolean lateStatic;
    private final List<Step> steps = new ArrayList<>();
    private Memory value;
    private PhpRuntimeOwnership.Frame runtimeFrame;
    private int runtimeSite = -1, runtimeRootLocal = -1;
    private boolean runtimeGlobals;

    private NullCoalesceAssignment(Memory root, String className, String staticProperty, boolean lateStatic,
                                   Environment env, TraceInfo trace) throws Throwable {
        this.root = root;
        this.className = className;
        this.staticProperty = staticProperty;
        this.lateStatic = lateStatic;
        this.env = env;
        this.trace = trace;
    }

    public static NullCoalesceAssignment variable(Memory root, Environment env, TraceInfo trace) throws Throwable {
        return new NullCoalesceAssignment(root, null, null, false, env, trace);
    }

    public static NullCoalesceAssignment variableRuntime(Memory root, Environment env, TraceInfo trace,
                                                         PhpRuntimeOwnership.Frame frame, int siteId, int rootLocalId) throws Throwable {
        if (frame != null) frame.requireArrayTarget(siteId,rootLocalId,root);
        NullCoalesceAssignment target=variable(root,env,trace);
        target.runtimeFrame=frame;target.runtimeSite=siteId;target.runtimeRootLocal=rootLocalId;
        return target;
    }

    public static NullCoalesceAssignment variableGlobalsRuntime(Memory root, Environment env, TraceInfo trace,
                                                                PhpRuntimeOwnership.Frame frame, int siteId, int rootLocalId) throws Throwable {
        if(frame!=null)frame.requireGlobalsTarget(siteId,rootLocalId,root);
        NullCoalesceAssignment target=variable(root,env,trace);
        target.runtimeFrame=frame;target.runtimeSite=siteId;target.runtimeRootLocal=rootLocalId;target.runtimeGlobals=true;
        return target;
    }

    public NullCoalesceAssignment indexGlobal(String literalKey) throws Throwable {
        return index(new StringMemory(literalKey));
    }

    public Memory assignGlobalRuntime(Memory assigned,PhpRuntimeOwnership.Frame frame,int sourceEvalId,int sourceLocalId,int mode) throws Throwable {
        if(frame==null)return mode==1?assignRef(assigned):assign(assigned);
        if(!runtimeGlobals||frame!=runtimeFrame||steps.size()!=1||steps.get(0).key==null
                ||steps.get(0).property!=null||steps.get(0).variable!=null)
            throw new IllegalArgumentException("Named GLOBALS requires its exact literal addressed target");
        return frame.assignGlobal(runtimeSite,runtimeRootLocal,root,steps.get(0).key,assigned,sourceEvalId,sourceLocalId,mode);
    }

    public static NullCoalesceAssignment staticProperty(String clazz, String property, boolean lateStatic,
                                                       Environment env, TraceInfo trace) throws Throwable {
        return new NullCoalesceAssignment(null, clazz, property, lateStatic, env, trace);
    }

    public NullCoalesceAssignment index(Memory key) throws Throwable {
        key = key.toImmutable();
        steps.add(new Step(key, null));
        return this;
    }

    public NullCoalesceAssignment indexVariable(Memory key, String name) {
        steps.add(new Step(key, null, name));
        return this;
    }

    public NullCoalesceAssignment propertyVariable(Memory name, String variable) {
        steps.add(new Step(name, null, variable, true));
        return this;
    }

    public NullCoalesceAssignment append() {
        steps.add(new Step(null, null));
        return this;
    }

    private Memory readIndex(Memory container, Memory key) throws Throwable {
        container = container.toValue();
        Memory result;
        if (container.isObject()) {
            if (!(container.toValue(ObjectMemory.class).value instanceof ArrayAccess))
                env.exception(trace, BaseError.class, "Cannot use object of type %s as array",
                        container.toValue(ObjectMemory.class).value.getReflection().getName());
            result = container.issetOfIndex(trace, key).isNull() ? Memory.NULL : container.valueOfIndex(trace, key).toValue();
        } else if (container.isString()) {
            Memory offset = key.isString() ? StringMemory.toNumericStrict(key.toString()) : key;
            byte[] bytes = container.getBinaryBytes(StandardCharsets.UTF_8);
            if (offset == null || key.isString() && offset.getRealType() != Memory.Type.INT || key.isArray() || key.isObject()) {
                result = Memory.NULL;
            } else {
                long position = offset.toLong();
                if (position < 0) position += bytes.length;
                result = position >= 0 && position < bytes.length ? new BinaryMemory(new byte[] {bytes[(int) position]}) : Memory.NULL;
            }
        } else {
            checkKey(container, key);
            result = container.issetOfIndex(trace, key).isNull() ? Memory.NULL : container.valueOfIndex(trace, key).toValue();
        }
        return result;
    }

    public NullCoalesceAssignment property(String name) throws Throwable {
        steps.add(new Step(null, name));
        return this;
    }

    private void checkKey(Memory container, Memory key) {
        if (container.isArray() && (key.isArray() || key.isObject()))
            env.exception(trace, BaseTypeError.class, "Cannot access offset of type %s on array",
                    key.isArray() ? "array" : key.toValue(ObjectMemory.class).getReflection().getName());
    }

    public boolean needsAssignment() throws Throwable {
        value = className == null ? root.toValue() : ObjectInvokeHelper.issetStaticProperty(className,
                className.toLowerCase(Locale.ROOT), staticProperty, env, trace, null, 0, lateStatic).toValue();
        for (Step step : steps) {
            if (step.property == null) value = readIndex(value, step.key);
            else value = ObjectInvokeHelper.getQuietProperty(value, step.property, env, trace, null, 0).toValue();
        }
        return value.isNull();
    }
    public Memory value() { return value.toImmutable(); }

    public Memory assign(Memory assigned) throws Throwable {
        return write(assigned, false);
    }

    public Memory assignRuntime(Memory assigned, PhpRuntimeOwnership.Frame frame, int sourceEvalId) throws Throwable {
        if (frame == null) return assign(assigned);
        if (frame != runtimeFrame || steps.size()!=1 || steps.get(0).property!=null || steps.get(0).variable!=null)
            throw new IllegalArgumentException("ARRAY ENTRY requires its exact single addressed target");
        frame.requireArrayTarget(runtimeSite,runtimeRootLocal,root);
        Memory value=assigned.toImmutable();
        frame.requireArrayValue(sourceEvalId,value);
        if (root.toValue().getRealType()==Memory.Type.BOOL && !root.toValue().toBoolean())
            return assignRuntimeFalse(value,frame,sourceEvalId);
        return write(value,false,frame,sourceEvalId);
    }

    private Memory assignRuntimeFalse(Memory assigned, PhpRuntimeOwnership.Frame frame, int sourceEvalId) throws Throwable {
        if (!(root instanceof php.runtime.memory.ReferenceMemory))
            throw new IllegalArgumentException("False ARRAY conversion needs its physical addressed CV");
        var carrier=(php.runtime.memory.ReferenceMemory)root;
        carrier.initializeArray();
        var converted=carrier.toValue(ArrayMemory.class);
        boolean survives=false;
        Throwable primary=null;
        var lease=converted.pinWriteFetch();
        try {
            try {env.error(trace,ErrorType.E_DEPRECATED,"Automatic conversion of false to array is deprecated");}
            catch(Throwable warning){primary=warning;}
            survives=lease.hasSurvivingValueOwners();
        } catch(Throwable failure){primary=frame.authority().domain().graph().composeFailure(primary,failure);}
        finally {
            try {lease.close();}
            catch(Throwable cleanup){primary=frame.authority().domain().graph().composeFailure(primary,cleanup);}
        }
        Memory result=Memory.NULL;
        try {
            if (!survives) frame.cancelArrayEntry(runtimeSite,runtimeRootLocal,root,sourceEvalId,assigned);
            else {
                frame.requireArrayTarget(runtimeSite,runtimeRootLocal,root);
                frame.requireArrayValue(sourceEvalId,assigned);
                result=frame.writableArrayTarget(runtimeSite,runtimeRootLocal,root)
                        .assignRuntimeEntry(steps.get(0).key,assigned,frame,sourceEvalId,env,trace);
            }
        } catch(Throwable failure){primary=frame.authority().domain().graph().composeFailure(primary,failure);}
        if(primary!=null)throw primary;
        return result;
    }

    public Memory assignRef(Memory assigned) throws Throwable {
        return write(assigned, true);
    }

    private Memory write(Memory assigned, boolean byReference) throws Throwable {
        return write(assigned,byReference,null,-1);
    }

    private Memory write(Memory assigned, boolean byReference, PhpRuntimeOwnership.Frame frame, int sourceEvalId) throws Throwable {
        Memory result = byReference && assigned instanceof php.runtime.memory.ReferenceMemory
                ? ((php.runtime.memory.ReferenceMemory) assigned).getReferenceForBinding()
                : byReference || frame != null ? assigned : assigned.toImmutable();
        Memory container = className == null ? root : steps.isEmpty() && !byReference
                ?ObjectInvokeHelper.directStaticProperty(className,className.toLowerCase(Locale.ROOT),staticProperty,env,trace,null,0,lateStatic)
                :ObjectInvokeHelper.getStaticProperty(className,className.toLowerCase(Locale.ROOT),staticProperty,env,trace,null,0,lateStatic);
        if (steps.isEmpty()) {
            if (byReference) container.assignRef(result); else result=container.assign(result);
        } else {
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                boolean last = i == steps.size() - 1;
                if (step.variable != null && step.key.isUndefined()) env.warning(trace, "Undefined variable $%s", step.variable);
                String property = step.dynamicProperty ? step.key.toString() : step.property;
                if (property == null) {
                    if(container instanceof php.runtime.memory.ReferenceMemory)
                        ((php.runtime.memory.ReferenceMemory)container).checkPhpReadonlyArrayWrite();
                    Memory actual = container.toValue();
                    if (actual.isNull() || actual.getRealType() == Memory.Type.BOOL && !actual.toBoolean()) {
                        if (!actual.isNull() && last) env.error(trace, ErrorType.E_DEPRECATED, "Automatic conversion of false to array is deprecated");
                        if (container instanceof php.runtime.memory.ReferenceMemory)
                            ((php.runtime.memory.ReferenceMemory)container).initializeArray();
                        else container.assign(new ArrayMemory());
                        actual = container.toValue();
                    }
                    Memory key = step.key == null ? null : step.key.toValue();
                    if (key != null) {
                        checkKey(actual, key);
                        if (actual.isArray() && key.isNull()) env.error(trace, ErrorType.E_DEPRECATED,
                                "Using null as an array offset is deprecated, use an empty string instead");
                        if (actual.isArray() && key.getRealType() == Memory.Type.DOUBLE) {
                            double real = key.toDouble();
                            long integer = PhpNumericConversions.floatToLong(real);
                            if (!Double.isFinite(real) || real < -0x1p63 || real >= 0x1p63 || real != integer)
                                env.error(trace, ErrorType.E_DEPRECATED, "Implicit conversion from float %s to int loses precision", key.toString());
                            key = php.runtime.memory.LongMemory.valueOf(integer);
                        }
                    }
                    if (actual.isObject() && !(actual.toValue(ObjectMemory.class).value instanceof ArrayAccess))
                        env.exception(trace, BaseError.class, "Cannot use object of type %s as array",
                                actual.toValue(ObjectMemory.class).value.getReflection().getName());
                    if (actual.isString()) {
                        if (key == null) env.exception(trace, BaseError.class, "[] operator not supported for strings");
                        if (byReference && last) env.exception(trace, BaseError.class, "Cannot create references to/from string offsets");
                        if (!last) {
                            PhpStringOffsets.index(key,env,trace);
                            env.exception(trace, BaseError.class, "Cannot use string offset as an array");
                        }
                        return assignStringOffset(container, key, result);
                    }
                    if (!actual.isArray() && !actual.isObject() && !actual.isString()
                            && !actual.isNull() && actual != Memory.FALSE)
                        env.exception(trace, BaseError.class, "Cannot use a scalar value as an array");
                    if (last && byReference && actual.isObject()) {
                        readOverloaded(actual, key);
                        env.exception(trace, BaseError.class, "Cannot assign by reference to an array dimension of an object");
                    }
                    if (frame != null && last && actual.isArray()) {
                        if (!(container instanceof php.runtime.memory.ReferenceMemory))
                            throw new IllegalArgumentException("ARRAY ENTRY needs its physical addressed CV carrier");
                        return frame.writableArrayTarget(runtimeSite,runtimeRootLocal,root)
                                .assignRuntimeEntry(key,result,frame,sourceEvalId,env,trace);
                    }
                    if (!last && actual.isObject()) {
                        container = readOverloaded(actual, key);
                    } else container = key == null
                            ? actual.isArray() ? actual.toValue(ArrayMemory.class).refOfPushPhp8(env, trace) : container.refOfPush(trace)
                            : container.refOfIndex(trace, key);
                    if (last) {
                        if (byReference) container.assignRef(result); else result=container.assign(result);
                    }
                } else {
                    if (!container.isObject())
                        env.exception(trace, BaseError.class, "Attempt to assign property \"%s\" on %s",
                                property, container.isNull() ? "null" : container.getRealType().toString().toLowerCase(Locale.ROOT));
                    if (last && byReference) ObjectInvokeHelper.getRefProperty(container, property, env, trace, null, 0).assignRef(result);
                    else if (last) result=ObjectInvokeHelper.assignProperty(container, result, property, env, trace, null, 0);
                    else container = ObjectInvokeHelper.getRefProperty(container, property, env, trace, null, 0);
                }
            }
        }
        return byReference ? result : result.toImmutable();
    }

    private Memory readOverloaded(Memory actual, Memory key) {
        Memory fetched = actual.valueOfIndex(trace, key == null ? Memory.NULL : key);
        if (!fetched.isReference() && !fetched.isObject()) {
            env.error(trace, ErrorType.E_NOTICE, "Indirect modification of overloaded element of %s has no effect",
                    actual.toValue(ObjectMemory.class).getReflection().getName());
            return new php.runtime.memory.ReferenceMemory(fetched.toImmutable());
        }
        return fetched;
    }

    private Memory assignStringOffset(Memory container, Memory key, Memory assigned) {
        return PhpStringOffsets.reference(container,trace,key,env).assign(assigned);
    }

    private static final class Step {
        final Memory key;
        final String property;
        final String variable;
        final boolean dynamicProperty;
        Step(Memory key, String property) { this(key, property, null, false); }
        Step(Memory key, String property, String variable) { this(key, property, variable, false); }
        Step(Memory key, String property, String variable, boolean dynamicProperty) {
            this.key = key; this.property = property; this.variable = variable; this.dynamicProperty = dynamicProperty;
        }
    }
}
