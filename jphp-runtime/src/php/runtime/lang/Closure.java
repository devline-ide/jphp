package php.runtime.lang;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.collections.map.HashedMap;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.Invoker;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.lang.support.IStaticVariables;
import php.runtime.lang.support.ICloneableObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static php.runtime.annotation.Reflection.*;

@Name("Closure")
public abstract class Closure extends BaseObject implements IStaticVariables, ICloneableObject<Closure>, Cloneable {
    protected Memory[] uses;
    private Map<String, ReferenceMemory> statics;
    protected Memory self = Memory.NULL;
    protected String scope = null;
    protected String calledClass;
    protected String declarationScope;
    protected boolean staticClosure;
    protected boolean usesThis;
    private MethodEntity reflectedMethod;
    private boolean reflectedBindingModern;

    /*public Closure(Environment env, ClassEntity closure, Memory self, Memory[] uses) {
        this(env, closure, self, null, uses);
    }*/

    public Closure(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }

    public Closure(Environment env, ClassEntity closure, Memory self, String scope, Memory[] uses) {
        super(closure);
        this.self = self;
        this.scope = scope;
        if (env != null) calledClass = env.getLateStatic();

        if (env != null && (this.scope == null || this.scope.isEmpty())) {
            this.scope = env.getLateStatic();
        }

        if (this.scope != null && this.scope.isEmpty()) {
            this.scope = null;
        }
        declarationScope = this.scope;

        this.uses = uses;
        php.runtime.PhpPrimitiveCellRuntime.closureConstructed(this,uses);
    }

    /** A clone owns distinct static slots, preserving only already-exported canonical reference cells. */
    @Override
    protected Closure clone() throws CloneNotSupportedException {
        php.runtime.PhpPrimitiveCellRuntime.closureOpaque(this,uses);
        if (getOwnershipObject() != null) throw new CloneNotSupportedException("Certified Closure clone requires its explicit holder allocation route");
        Closure copy = (Closure)super.clone();
        if (hasPhp8Binding() && statics != null) {
            copy.statics = new HashMap<>();
            for (Map.Entry<String, ReferenceMemory> entry : statics.entrySet()) {
                ReferenceMemory slot = entry.getValue();
                Memory raw = slot.getRawValue();
                Memory value = slot.isCanonicalReferenceCell() ? slot
                        : raw instanceof ReferenceMemory && ((ReferenceMemory)raw).isCanonicalReferenceCell()
                        ? raw : raw.toImmutable();
                value = php.runtime.PhpPrimitiveCellRuntime.cloneStaticValue(this,entry.getKey(),slot,value);
                ReferenceMemory carrier = new ReferenceMemory(value);
                php.runtime.PhpPrimitiveCellRuntime.clonedStatic(copy,entry.getKey(),carrier,value);
                copy.statics.put(entry.getKey(), carrier);
            }
        }
        return copy;
    }

    @Override
    @Ignore
    public Closure __clone(Environment env, php.runtime.env.TraceInfo trace) {
        try {
            // Preserve the legacy factory route; modern closures require their five-argument constructor state.
            return hasPhp8Binding() ? clone() : getReflection().cloneObject(this, env, trace);
        } catch (Throwable failure) {
            Closure.<RuntimeException>raiseCloneFailure(failure);return null;
        }
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void raiseCloneFailure(Throwable failure) throws T {throw (T)failure;}

    @Signature
    abstract public Memory __invoke(Environment env, Memory... args) throws Throwable;

    /** Read-only bookkeeping traversal; the capture array/map itself is never exposed. */
    @Ignore public final void appendMemoryRoots(java.util.Deque<Memory> roots) {
        if(uses!=null)for(Memory value:uses)if(value!=null)roots.add(value);
        if(self!=null)roots.add(self);
        if(statics!=null)roots.addAll(statics.values());
    }

    /** Invalidate actual captured CELLs before an opaque Java wrapper publishes this Closure. */
    @Ignore public final void invalidatePrimitiveCaptures() {
        php.runtime.PhpPrimitiveCellRuntime.closureOpaque(this,uses);
    }

    public Memory[] getUses() {
        php.runtime.PhpPrimitiveCellRuntime.closureOpaque(this,uses);
        if (getOwnershipObject() != null) {
            ownershipClosure().validate();Memory[] snapshot=uses == null ? new Memory[0] : uses.clone();
            for (int i=0;i<snapshot.length;i++) if (snapshot[i] instanceof ObjectMemory)
                snapshot[i]=new ObjectMemory(((ObjectMemory)snapshot[i]).value);
            return snapshot;
        }
        return uses == null ? new Memory[0] : uses;
    }
    @Ignore public final php.runtime.PhpOwnershipClosures.Certificate ownershipClosure() {
        if (getOwnershipObject() == null || getOwnershipObject().closure() == null)
            throw new IllegalStateException("Closure has no complete holder protocol");
        return getOwnershipObject().closure();
    }
    /** Exact descriptor stores; no PHP callbacks or virtual getters inside publication. */
    @Ignore public final void initializeOwnershipHolders(php.runtime.PhpOwnershipClosures.Certificate certificate,
                                                        Memory[] emptyCaptures, Map<String,ReferenceMemory> storage,
                                                        String lexicalScope, boolean staticFlag, boolean readsThis) {
        if (getOwnershipObject() == null || getOwnershipObject().closure() != certificate || uses.length != 0 || !self.isNull() || statics != null || !certificate.consumeInitialization(this))
            throw new IllegalStateException("Closure is not its witnessed empty shell");
        uses=emptyCaptures;statics=storage;scope=lexicalScope;declarationScope=lexicalScope;
        staticClosure=staticFlag;usesThis=readsThis;
    }
    @Ignore public final void publishOwnershipCapture(php.runtime.PhpOwnershipClosures.RawStore store) {
        int index=store.index(this);uses[index]=store.consume(this,index);
    }
    @Ignore public final void publishOwnershipSelf(php.runtime.PhpOwnershipClosures.RawStore store) {
        self=store.consume(this,-1);
    }

    @Signature({@Arg("prop"), @Arg("value")})
    public Memory __set(Environment env, Memory... args){
        env.error(ErrorType.E_ERROR, "Closure object cannot have properties");
        return Memory.NULL;
    }

    @Signature({@Arg("prop")})
    public Memory __get(Environment env, Memory... args){
        return __set(env, args);
    }

    @Signature({@Arg("prop")})
    public Memory __unset(Environment env, Memory... args){
        return __set(env, args);
    }

    @Signature({@Arg("prop")})
    public Memory __isset(Environment env, Memory... args){
        return __set(env, args);
    }

    @Override
    public Memory getStatic(String name){
        if (statics == null)
            return null;

        ReferenceMemory result=statics.get(name);
        if (getOwnershipObject()==null) php.runtime.PhpPrimitiveCellRuntime.staticExposed(result);
        return result;
    }

    /** Capability is held only by the physical CELL runtime; no publication permit is allocated for ordinary static reads. */
    @Ignore
    public final Memory readPrimitiveStatic(php.runtime.PhpPrimitiveCellRuntime.ReadAccess access,String key,Memory initial,boolean create) {
        access.validate();
        if (getOwnershipObject()!=null) return create ? getOrCreateStatic(key,initial) : getStatic(key);
        if (statics==null) { if (!create) return null;statics=new HashMap<>(); }
        ReferenceMemory slot=statics.get(key);
        if (slot==null && create) {slot=new ReferenceMemory(initial);statics.put(key,slot);}
        return slot;
    }

    public String getScope() {
        return scope;
    }

    public String getCalledClass() {
        return calledClass == null || calledClass.isEmpty() ? scope : calledClass;
    }

    public String getDeclarationScope() {
        return declarationScope;
    }

    public boolean hasPhp8Binding() {
        ClassEntity entity = getReflection();
        return reflectedBindingModern || entity.getModule() != null
                && entity.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
    }

    /** Reflected factories expose their receiver through self, never a hidden Java capture. */
    public final void initializeReflectionBinding(MethodEntity method, boolean modern) {
        reflectedMethod = method; reflectedBindingModern = modern;
        scope = method == null ? null : method.getClazz().getName();
        declarationScope = scope;
        staticClosure = method != null && method.isStatic();
        usesThis = method != null && !method.isStatic();
        calledClass = method == null ? null : !staticClosure && self.isObject()
                ? self.toValue(ObjectMemory.class).getReflection().getName() : scope;
    }

    public final void initializeAcquiredBinding(MethodEntity method, String acquiredClass) {
        initializeReflectionBinding(method, true);
        if (acquiredClass != null) calledClass = acquiredClass;
    }

    public final MethodEntity getReflectedMethod() { return reflectedMethod; }

    public Memory getSelf() {
        if (getOwnershipObject() != null) {
            ownershipClosure().validate();return self instanceof ObjectMemory ? new ObjectMemory(((ObjectMemory)self).value) : self;
        }
        return self;
    }

    public boolean isStaticClosure() {
        return staticClosure;
    }

    public void setStaticClosure(boolean staticClosure) {
        this.staticClosure = staticClosure;
    }

    public void setUsesThis(boolean usesThis) {
        this.usesThis = usesThis;
    }

    public Memory getOrCreateStatic(String name, Memory initValue){
        if (getOwnershipObject() != null) return ownershipClosure().staticStorage().getOrCreate(name,ownershipClosure().semanticValue(initValue));
        if (statics == null)
            statics = new HashMap<>();

        ReferenceMemory result = statics.get(name);
        if (result == null) {
            result = new ReferenceMemory(initValue);
            statics.put(name, result);
        }

        php.runtime.PhpPrimitiveCellRuntime.staticExposed(result);
        return result;
    }

    @Signature({
            @Arg("newThis"),
            @Arg(value = "parameters", type = HintType.VARARG, optional = @Optional("null"))
    })
    public Memory call(Environment env, Memory... args) throws Throwable {
        if (getOwnershipObject() != null) throw new IllegalStateException("Certified Closure call requires an owned invocation view");
        if (hasPhp8Binding()) {
            if (!args[0].isObject()) env.exception(php.runtime.lang.exception.BaseTypeError.class,
                    "Closure::call(): Argument #1 must be of type object");
            if (args[0].toValue(ObjectMemory.class).getReflection().isInternal()) {
                env.warning(env.trace(), "Cannot bind closure to scope of internal class %s", args[0].toValue(ObjectMemory.class).getReflection().getName());
                return Memory.NULL;
            }
        } else ParameterEntity.validateTypeHinting(env, 1, args, HintType.OBJECT, true);
        if (hasPhp8Binding() && staticClosure) {
            env.warning(env.trace(), "Cannot bind an instance to a static closure");
            return Memory.NULL;
        }

        // call() temporarily binds this same closure; its static variables remain in the original table.
        if (hasPhp8Binding() && statics == null) statics = new HashMap<>();
        Closure newClosure = hasPhp8Binding() ? (Closure) super.clone() : (Closure) this.clone();
        newClosure.self = args[0];
        newClosure.scope = newClosure.self.toValue(ObjectMemory.class).getReflection().getName();
        if (hasPhp8Binding()) newClosure.calledClass = newClosure.scope;

        return ObjectInvokeHelper.invokeMethod(
                newClosure, newClosure.getReflection().methodMagicInvoke, env, env.trace(),
                Arrays.copyOfRange(args, 1, args.length)
        );
    }

    @Signature({@Arg("newThis"), @Arg(value = "newScope", optional = @Optional("static"))})
    public Memory bindTo(Environment env, Memory... args) throws CloneNotSupportedException {
        if (hasPhp8Binding()) return bindModern(env, args[0], args[1]);
        ParameterEntity.validateTypeHinting(env, 1, args, HintType.OBJECT, true);

        Closure newClosure = (Closure) this.clone();
        newClosure.self = args[0];
        newClosure.scope = args[1].toString();
        return new ObjectMemory(newClosure);
    }

    private Memory bindModern(Environment env, Memory newThis, Memory newScope) throws CloneNotSupportedException {
        newThis = newThis.toValue();
        newScope = newScope.toValue();
        if (!newThis.isNull() && !newThis.isObject()) env.exception(php.runtime.lang.exception.BaseTypeError.class,
                "Closure binding object must be of type ?object");
        if (newScope.isNumber() || newScope.type == Memory.Type.BOOL) {
            php.runtime.reflection.ModuleEntity caller = env.getModuleManager().findModule(env.trace());
            if (caller == null || !caller.isStrictTypes()) newScope = StringMemory.valueOf(newScope.toString());
        }
        if (staticClosure && !newThis.isNull()) {
            env.warning(env.trace(), "Cannot bind an instance to a static closure");
            return Memory.NULL;
        }
        if (reflectedMethod != null && !staticClosure && newThis.isNull()) {
            env.warning(env.trace(), "Cannot unbind $this of method, this will be an error in PHP 9");
            return Memory.NULL;
        }
        if (usesThis && !staticClosure && newThis.isNull()) {
            env.warning(env.trace(), "Cannot unbind $this of closure using $this, this will be an error in PHP 9");
            return Memory.NULL;
        }
        String scopeName;
        if (newScope.isNull()) scopeName = null;
        else if (newScope.isObject()) scopeName = newScope.toValue(ObjectMemory.class).getReflection().getName();
        else if (newScope.isString()) {
            scopeName = newScope.toString();
            if (scopeName.equals("static")) scopeName = scope;
            else {
                ClassEntity scopeClass = env.fetchClass(scopeName, true);
                if (scopeClass == null) {
                    env.warning(env.trace(), "Class \"%s\" not found", scopeName);
                    return Memory.NULL;
                }
                scopeName = scopeClass.getName();
            }
        } else {
            env.exception(php.runtime.lang.exception.BaseTypeError.class,
                    "Closure binding scope must be of type object|string|null");
            return Memory.NULL;
        }
        if (scopeName != null && !(newScope.isString() && newScope.toString().equals("static"))) {
            ClassEntity scopeClass = env.fetchClass(scopeName, false);
            if (scopeClass != null && scopeClass.isInternal()) {
                env.warning(env.trace(), "Cannot bind closure to scope of internal class %s", scopeName);
                return Memory.NULL;
            }
        }
        if (reflectedMethod != null) {
            if (!java.util.Objects.equals(scopeName, reflectedMethod.getClazz().getName())) {
                env.warning(env.trace(), "Cannot rebind scope of closure created from method, this will be an error in PHP 9");
                return Memory.NULL;
            }
            if (!staticClosure && !newThis.toValue(ObjectMemory.class).getReflection().isInstanceOf(reflectedMethod.getClazz())) {
                env.warning(env.trace(), "Cannot bind method %s::%s() to object of class %s, this will be an error in PHP 9",
                        reflectedMethod.getClazz().getName(), reflectedMethod.getName(), newThis.toValue(ObjectMemory.class).getReflection().getName());
                return Memory.NULL;
            }
        }
        Closure copy = (Closure) clone();
        copy.self = newThis;
        copy.scope = scopeName == null && newThis.isObject() ? "Closure" : scopeName;
        copy.calledClass = newThis.isObject() ? newThis.toValue(ObjectMemory.class).getReflection().getName() : scopeName;
        return ObjectMemory.valueOf(copy);
    }

    @Signature({
            @Arg(value = "closure", typeClass = "Closure"),
            @Arg(value = "newThis"),
            @Arg(value = "newScope", optional = @Optional("static"))
    })
    public static Memory bind(Environment env, Memory... args) throws CloneNotSupportedException {
        Closure closure = args[0].toObject(Closure.class);
        if (closure.hasPhp8Binding()) return closure.bindModern(env, args[1], args[2]);
        ParameterEntity.validateTypeHinting(env, 2, args, HintType.OBJECT, true);

        Closure newClosure = (Closure) closure.clone();
        newClosure.self = args[1];
        newClosure.scope = args[2].toString();
        return new ObjectMemory(newClosure);
    }

    @Signature(@Arg(value = "callable", type = HintType.CALLABLE))
    public static Memory fromCallable(Environment env, Memory... args) throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(env.trace()) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            return php.runtime.invoke.PhpCallableFactory.fromCallable(args[0], env, env.trace());
        Invoker invoker = Invoker.create(env, args[0]);

        return ObjectMemory.valueOf(new ClosureInvoker(env, invoker));
    }

    @Name("php\\lang\\ClosureInvoker")
    public static class ClosureInvoker extends Closure {
        private Invoker invoker;

        public ClosureInvoker(Environment env, ClassEntity clazz) {
            super(env, clazz);
        }

        public ClosureInvoker(Environment env, Invoker invoker) {
            super(env, env.fetchClass(ClosureInvoker.class), Memory.NULL, "", new Memory[0]);
            this.invoker = invoker;
        }

        @Signature
        private void __construct() {
        }

        @Override
        @Signature
        public Memory __invoke(Environment env, Memory... args) throws Throwable {
            return invoker.forEnvironment(env).call(args);
        }

        @Override
        public Memory getOrCreateStatic(String name) {
            return Memory.NULL;
        }
    }
}
