package php.runtime.reflection;

import php.runtime.common.PhpClassNames;

import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.common.Messages;
import php.runtime.common.Modifier;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.ConcurrentEnvironment;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.exceptions.FatalException;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.support.Extension;
import php.runtime.invoke.InvokeArgumentHelper;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.invoke.cache.PropertyCallCache;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;
import php.runtime.lang.support.MagicSignatureClass;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.support.ArrayMapEntryMemory;
import php.runtime.reflection.support.Entity;
import php.runtime.reflection.support.ReflectionUtils;
import php.runtime.reflection.support.TypeChecker;
import php.runtime.wrap.ClassWrapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class ClassEntity extends Entity implements Cloneable {
    private final static int FLAG_GET = 4000;
    private final static int FLAG_SET = 4001;
    private final static int FLAG_ISSET = 4002;
    private final static int FLAG_UNSET = 4003;

    // types
    public enum Type {
        CLASS, INTERFACE, TRAIT, CLOSURE, FUNCTION, GENERATOR
    }

    private long id;

    protected int methodCounts = 0;
    protected boolean isInternal;
    protected boolean isNotRuntime;

    protected Extension extension;
    protected Class<?> nativeClazz;

    protected Constructor nativeConstructor;
    protected Method nativeInitEnvironment;

    protected ModuleEntity module;

    protected final Map<String, MethodEntity> methods;
    public MethodEntity methodConstruct;
    public MethodEntity methodDestruct;

    public MethodEntity methodMagicSet;
    public MethodEntity methodMagicGet;
    public MethodEntity methodMagicUnset;
    public MethodEntity methodMagicIsset;
    public MethodEntity methodMagicCall;
    public MethodEntity methodMagicCallStatic;
    public MethodEntity methodMagicInvoke;
    public MethodEntity methodMagicToString;
    public MethodEntity methodMagicClone;
    public MethodEntity methodMagicSleep;
    public MethodEntity methodMagicWakeup;
    public MethodEntity methodMagicDebugInfo;

    protected MethodEntity constructor;

    protected final Map<String, ClassEntity> interfaces;
    protected final Map<String, ClassEntity> traits;
    private final List<TraitConstantCheck> traitConstantChecks = new ArrayList<>();
    private boolean deferredInitialization;
    public boolean hasDeferredInitialization() { return deferredInitialization; }
    public void setDeferredInitialization(boolean value) { deferredInitialization = value; }

    public void initializeRuntimeState(Environment env) {
        if (!deferredInitialization) return;
        synchronized (env.getClassInitializationLock()) {
            String key = "\0class-initialization:" + PhpClassNames.canonicalKey(getName());
            if (env.getUserValue(key, Boolean.class) != null) return;
            env.setUserValue(key, Boolean.FALSE);
            try {
                if (parent != null) parent.initializeRuntimeState(env);
                for (ConstantEntity constant : getConstants()) constant.getValue(env);
                initEnvironmentBody(env);
                if (hasPhp8Signatures()) {
                    for (PropertyEntity property : getProperties())
                        if (property.getClazz() == this && property.getDeclaredType() != null && property.isDefault())
                            property.getDefaultValue(env);
                    for (PropertyEntity property : getStaticProperties())
                        if (property.getClazz() == this && property.getDeclaredType() != null && property.isDefault())
                            property.getDefaultValue(env);
                }
                env.setUserValue(key, Boolean.TRUE);
            } catch (Throwable error) {
                env.setUserValue(key, null);
                env.forwardThrow(error);
            }
        }
    }

    public static final class TraitConstantCheck {
        public final String name;
        public final String trait;
        public final Memory value;
        public final String owner;
        private TraitConstantCheck(String name, String trait, Memory value, String owner) {
            this.name = name; this.trait = trait; this.value = value; this.owner = owner;
        }
    }

    public List<TraitConstantCheck> getTraitConstantChecks() { return Collections.unmodifiableList(traitConstantChecks); }

    public void addTraitConstantCheck(String name, String trait, Memory value) {
        addTraitConstantCheck(name, trait, value, null);
    }

    public void addTraitConstantCheck(String name, String trait, Memory value, String owner) {
        traitConstantChecks.add(new TraitConstantCheck(name, trait, value, owner));
    }

    public void traitConstantConflict(Environment env, String name, String trait) {
        ConstantEntity constant = findConstant(name);
        traitConstantConflict(env, name, trait, constant.getTraitName() == null ? getName() : constant.getTraitName());
    }

    private void traitConstantConflict(Environment env, String name, String trait, String owner) {
        env.error(getTrace(), ErrorType.E_COMPILE_ERROR,
                "%s and %s define the same constant (%s) in the composition of %s. However, the definition differs and is considered incompatible. Class was composed",
                owner, trait, name, getName());
    }

    private void checkTraitConstant(Environment env, TraitConstantCheck check) {
        try {
            Memory value = check.value;
            if (value == null && deferredInitialization) {
                ConstantEntity candidate = env.fetchClass(check.trait).findConstant(check.name);
                if (candidate.getInitializerMethod() != null) value = candidate.evaluateInitializer(env, this);
            }
            ConstantEntity origin = findConstant(check.name);
            Memory original = origin.getInitializerMethod() == null ? origin.getValue(env) : origin.evaluateInitializer(env, this);
            if (value == null || original.identical(value)) return;
        } catch (php.runtime.lang.exception.BaseError error) {
            // Composition compares initializer values before checking their declared types.
        }
        if (check.owner == null) traitConstantConflict(env, check.name, check.trait);
        else traitConstantConflict(env, check.name, check.trait, check.owner);
    }

    public void addTraitDynamicConstant(Environment env, String name, Memory value) {
        ConstantEntity constant = findConstant(name);
        if (constant.getValue() != null || env.getStatic(constant.getInternalName()) != null) {
            String trait = env.peekCall(0).function;
            String owner = null;
            for (TraitConstantCheck check : traitConstantChecks)
                if (check.name.equals(name) && check.trait.equals(trait)) { owner = check.owner; break; }
            if (!constant.getValue(env).identical(value)) {
                if (owner == null) traitConstantConflict(env, name, trait);
                else traitConstantConflict(env, name, trait, owner);
            }
        } else env.getOrCreateStatic(constant.getInternalName(), value);
    }

    public final Map<String, ConstantEntity> constants;
    public final Map<String, PropertyEntity> properties;
    public final Map<String, PropertyEntity> staticProperties;
    public final Set<String> instanceOfList = new HashSet<String>();

    protected ClassEntity parent;
    protected DocumentComment docComment;

    protected boolean isAbstract = false;
    protected boolean isFinal = false;
    protected Type type = Type.CLASS;

    protected boolean isStatic;
    private boolean nativeAsciiClassIdentity;

    protected static final ClassEntity magicSignatureClass =
            new ClassEntity(new ClassWrapper(null, MagicSignatureClass.class));

    public ClassEntity(Context context) {
        super(context);
        this.methods = new LinkedHashMap<String, MethodEntity>();
        this.interfaces = new LinkedHashMap<String, ClassEntity>();
        this.traits = new LinkedHashMap<String, ClassEntity>();
        this.properties = new LinkedHashMap<String, PropertyEntity>();
        this.staticProperties = new LinkedHashMap<String, PropertyEntity>();
        this.constants = new LinkedHashMap<String, ConstantEntity>();
        this.isInternal = false;
    }

    public ClassEntity(ClassWrapper wrapper) {
        this((Context) null);
        nativeAsciiClassIdentity = wrapper.getScope() != null && wrapper.getScope().isModernClassLookup();
        wrapper.onWrap(this);
    }

    public void setExtension(Extension extension) {
        this.extension = extension;
    }

    public String getCompiledInternalName() {
        return super.getInternalName();
    }

    @Override
    public String getInternalName() {
        /*if (isTrait()) { depricated check, todo remove.
            throw new CriticalException("Disable of using internal names for traits, trait '" + getName() + "'");
        }   */

        return super.getInternalName();
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public boolean isInternal() {
        return isInternal;
    }

    public boolean isTrait() {
        return type == Type.TRAIT;
    }

    public void setInternal(boolean isInternal) {
        this.isInternal = isInternal;
    }

    public boolean isNotRuntime() {
        return isNotRuntime;
    }

    public void setNotRuntime(boolean isNotRuntime) {
        this.isNotRuntime = isNotRuntime;
    }

    public void doneDeclare() {
        if (hasPhp8Signatures()) {
            Map<String, ConstantEntity> ordered = new LinkedHashMap<>();
            for (ConstantEntity constant : constants.values()) if (constant.getClazz() == this) ordered.put(constant.getName(), constant);
            for (ConstantEntity constant : constants.values()) if (constant.getClazz() != this) ordered.put(constant.getName(), constant);
            constants.clear(); constants.putAll(ordered);
        }
        switch (getType()) {
            case CLOSURE:
            case GENERATOR:
            case CLASS:
                methodConstruct = methods.get("__construct");
                if (methodConstruct != null
                        && (methodConstruct.getPrototype() == null || !methodConstruct.getPrototype().isAbstractable()))
                    methodConstruct.setDynamicSignature(true);

                methodDestruct = methods.get("__destruct");

                methodMagicSet = methods.get("__set");
                methodMagicGet = methods.get("__get");
                methodMagicUnset = methods.get("__unset");
                methodMagicIsset = methods.get("__isset");
                methodMagicCall = methods.get("__call");
                methodMagicCallStatic = methods.get("__callstatic");

                methodMagicInvoke = methods.get("__invoke");
                methodMagicToString = methods.get("__tostring");
                methodMagicClone = methods.get("__clone");

                methodMagicSleep = methods.get("__sleep");
                methodMagicWakeup = methods.get("__wakeup");

                methodMagicDebugInfo = methods.get("__debuginfo");
                break;
        }
    }

    /*
ClassReader classReader;
            if (data != null)
                classReader = new ClassReader(data);
            else {
                try {
                    classReader = new ClassReader(nativeClazz.getName());
                } catch (IOException e) {
                    throw new CriticalException(e);
                }
            }
            ClassNode classNode = new ClassNode();
            classReader.accept(classNode, 0);

            return cachedClassNode = classNode;
     */

    public Extension getExtension() {
        return extension;
    }

    public boolean isDeprecated() {
        return false; // TODO
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean anAbstract) {
        isAbstract = anAbstract;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public void setFinal(boolean aFinal) {
        isFinal = aFinal;
    }

    public Type getType() {
        return type;
    }

    public boolean isInterface() {
        return type == Type.INTERFACE;
    }

    public boolean isClass() {
        return type == Type.CLASS;
    }

    public boolean isHiddenInCallStack() {
        return false;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Map<String, MethodEntity> getMethods() {
        return methods;
    }

    public int getMethodCounts() {
        return methodCounts;
    }

    public void __setMethodCounts(int methodCounts) {
        this.methodCounts = methodCounts;
    }

    public List<MethodEntity> getOwnedMethods() {
        List<MethodEntity> result = new ArrayList<MethodEntity>();
        for (MethodEntity el : methods.values()) {
            if (el.isOwned(this))
                result.add(el);
        }
        return result;
    }

    public SignatureResult addMethod(MethodEntity method, String realName) {
        String name = realName == null ? method.getLowerName() : realName;

        SignatureResult addResult = new SignatureResult();
        if (method.isAbstract && method.isFinal) {
            addResult.add(InvalidMethod.error(InvalidMethod.Kind.FINAL_ABSTRACT, method));
        } else if (method.isAbstractable() && !(method.isAbstract || type == Type.INTERFACE)) {
            addResult.add(InvalidMethod.error(InvalidMethod.Kind.NON_ABSTRACT, method));
        } else if (method.isAbstract && !method.isAbstractable()) {
            addResult.add(InvalidMethod.error(InvalidMethod.Kind.NON_ABSTRACTABLE, method));
        } else if (method.isAbstract && !(this.isAbstract || isTrait())) {
            addResult.add(InvalidMethod.error(InvalidMethod.Kind.NON_EXISTS, method));
        } else if (type == Type.INTERFACE &&
                (method.modifier != Modifier.PUBLIC || method.isFinal)) {
            addResult.add(InvalidMethod.error(InvalidMethod.Kind.INVALID_ACCESS_FOR_INTERFACE, method));
        }

        if (magicSignatureClass != null) {
            MethodEntity systemMethod = magicSignatureClass.findMethod(name.toLowerCase());
            if (systemMethod != null && method.clazz.getId() == getId()) {
                if (method.prototype == null)
                    method.setPrototype(systemMethod);

                if (systemMethod.getModifier() == Modifier.PUBLIC && method.getModifier() != Modifier.PUBLIC) {
                    addResult.add(InvalidMethod.warning(InvalidMethod.Kind.MAGIC_MUST_BE_PUBLIC, method));
                    //method.setModifier(Modifier.PUBLIC);
                }

                if (!systemMethod.equalsBySignature(method, false)) {
                    addResult.add(InvalidMethod.error(InvalidMethod.Kind.INVALID_SIGNATURE, method));
                } else if (systemMethod.isStatic && !method.isStatic)
                    addResult.add(InvalidMethod.warning(InvalidMethod.Kind.MUST_STATIC, method));
                else if (!systemMethod.isStatic && method.isStatic) {
                    //method.setStatic(false);
                    addResult.add(InvalidMethod.warning(InvalidMethod.Kind.MUST_NON_STATIC, method));
                }
            }
        }

        if (parent == null || (!name.equals(parent.lowerName) || !methods.containsKey(name))) {
            this.methods.put(name, method);
        }

        if (name.equals(lowerName) && !isTrait() && !hasPhp8Signatures()) {
            methods.put("__construct", method);
        }

        return addResult;
    }

    public int nextMethodIndex() {
        return methodCounts++;
    }

    public MethodEntity findMethod(String name) {
        return methods.get(name);
    }

    public ConstantEntity findConstant(String name) {
        return constants.get(name);
    }

    // use can pass specific names
    public PropertyEntity findProperty(String name) {
        int pos = name.lastIndexOf('\0');
        if (pos > -1 && pos + 1 < name.length())
            name = name.substring(pos + 1);

        return properties.get(name);
    }

    public PropertyEntity findStaticProperty(String name) {
        return staticProperties.get(name);
    }

    public ClassEntity getParent() {
        return parent;
    }

    public boolean isInstanceOf(Class<? extends IObject> clazz) {
        return isInstanceOf(ReflectionUtils.getClassName(clazz));
    }

    public boolean isInstanceOf(ClassEntity what) {
        if (what == null) return false;
        if (usesAsciiClassNames() || PhpClassNames.isModern(what)) return isInstanceOfAsciiName(what.getName());
        return id == what.id || instanceOfList.contains(what.lowerName);
    }

    public boolean isInstanceOf(String name) {
        if (name == null)
            throw new IllegalArgumentException();

        if (usesAsciiClassNames()) return isInstanceOfAsciiName(name);
        String lowerName = name.toLowerCase();
        return instanceOfList.contains(lowerName) || this.lowerName.equals(lowerName);
    }

    /** Uses original names even when a modern class inherits legacy metadata. */
    public boolean isInstanceOfAsciiName(String name) {
        String key = PhpClassNames.canonicalKey(name);
        if (PhpClassNames.canonicalKey(getName()).equals(key)) return true;
        if (parent != null && parent.isInstanceOfAsciiName(name)) return true;
        for (ClassEntity contract : interfaces.values()) {
            if (contract.isInstanceOfAsciiName(name)) return true;
        }
        return false;
    }

    private void inheritClassIdentity(ClassEntity parent) {
        if (!usesAsciiClassNames()) {
            instanceOfList.add(parent.getLowerName());
            instanceOfList.addAll(parent.instanceOfList);
            interfaces.putAll(parent.interfaces);
            return;
        }
        for (ClassEntity ancestor = parent; ancestor != null; ancestor = ancestor.parent)
            instanceOfList.add(PhpClassNames.canonicalKey(ancestor.getName()));
        for (ClassEntity contract : parent.interfaces.values()) {
            String key = PhpClassNames.canonicalKey(contract.getName());
            instanceOfList.add(key); interfaces.put(key, contract);
        }
    }

    public boolean isInstanceOfLower(String lowerName) {
        if (lowerName == null)
            throw new IllegalArgumentException();
        if (usesAsciiClassNames()) return isInstanceOfAsciiName(lowerName);
        return instanceOfList.contains(lowerName) || this.lowerName.equals(lowerName);
    }

    private void collectConstantOverrides(SignatureResult result) {
        if (parent != null) {
            for (ConstantEntity constant : parent.getConstants()) {
                ConstantEntity implConstant = findConstant(constant.getName());

                if (implConstant == constant) continue;

                if (implConstant != null) {
                    if (constant.isFinal()) {
                        result.addConstant(InvalidConstant.compile(InvalidConstant.Kind.FINAL, implConstant, constant));
                    } else if (constant.isPublic() && !implConstant.isPublic()) {
                        result.addConstant(hasPhp8Signatures()
                                ? InvalidConstant.compile(InvalidConstant.Kind.MUST_BE_PUBLIC, implConstant, constant)
                                : InvalidConstant.error(InvalidConstant.Kind.MUST_BE_PUBLIC, implConstant, constant));
                    } else if (constant.isProtected() && implConstant.isPrivate()) {
                        result.addConstant(hasPhp8Signatures()
                                ? InvalidConstant.compile(InvalidConstant.Kind.MUST_BE_PROTECTED, implConstant, constant)
                                : InvalidConstant.error(InvalidConstant.Kind.MUST_BE_PROTECTED, implConstant, constant));
                    }
                }
            }
        }
    }

    public void validateConstantInheritance(Environment env) {
        SignatureResult result = new SignatureResult();
        collectConstantOverrides(result);
        result.check(env);
    }

    public SignatureResult updateParentBody() {
        SignatureResult result = new SignatureResult();
        collectConstantOverrides(result);

        if (parent != null) {
            for (Map.Entry<String, MethodEntity> entry : parent.getMethods().entrySet()) {
                MethodEntity implMethod = findMethod(entry.getKey());
                MethodEntity method = entry.getValue();
                if (implMethod == method) continue;

                if (implMethod == null) {
                    SignatureResult addResult = addMethod(method, entry.getKey());
                    if (methodConstruct == null && !isTrait() && !hasPhp8Signatures()
                            && method.getName().equalsIgnoreCase(parent.getName())) {
                        if (!method.isAbstractable() && !methods.containsKey("__construct")) {
                            method.setDynamicSignature(true);
                            methods.put("__construct", method);
                        }
                    }

                    result.methods.addAll(addResult.methods);
                } else {
                    if (hasPhp8Signatures() && method.isPrivate()) continue;
                    implMethod.setPrototype(method);
                    if (method.isFinal)
                        result.add(InvalidMethod.error(InvalidMethod.Kind.FINAL, implMethod));

                    if (!isAbstract && method.isAbstract && implMethod.isAbstract)
                        result.add(InvalidMethod.error(InvalidMethod.Kind.NON_EXISTS, implMethod));

                    if (!hasPhp8Signatures() && !implMethod.equalsBySignature(method)) {
                        if (!method.isDynamicSignature() || method.isAbstractable()) {
                            boolean isWarning = true;
                            MethodEntity pr = method;

                            while (pr != null) {
                                if (pr.isAbstractable()) {
                                    isWarning = false;
                                    break;
                                }
                                pr = pr.getPrototype();
                            }

                            if (isWarning) {
                                TypeChecker implTypeChecker = implMethod.getReturnTypeChecker();
                                TypeChecker typeChecker = method.getReturnTypeChecker();

                                if (typeChecker != implTypeChecker && (typeChecker == null || implTypeChecker == null)) {
                                    isWarning = false;
                                } else if (typeChecker != null &&
                                        !typeChecker.getSignature().equals(implTypeChecker.getSignature())) {
                                    isWarning = false;
                                }
                            }

                            result.add(
                                    !isWarning
                                            ? InvalidMethod.error(InvalidMethod.Kind.INVALID_SIGNATURE, implMethod)
                                            : InvalidMethod.warning(InvalidMethod.Kind.INVALID_SIGNATURE, implMethod)
                            );
                        }
                    } else if (implMethod.isStatic() && !method.isStatic()) {
                        result.add(InvalidMethod.error(InvalidMethod.Kind.MUST_NON_STATIC, implMethod));
                    } else if (!implMethod.isStatic() && method.isStatic()) {
                        result.add(InvalidMethod.error(InvalidMethod.Kind.MUST_STATIC, implMethod));
                    }

                    if (method.isPublic() && !implMethod.isPublic())
                        result.add(InvalidMethod.error(InvalidMethod.Kind.MUST_BE_PUBLIC, implMethod));
                    else if (method.isProtected() && implMethod.isPrivate())
                        result.add(InvalidMethod.error(InvalidMethod.Kind.MUST_BE_PROTECTED, implMethod));
                }
            }

            doneDeclare();
        }
        return result;
    }

    public ExtendsResult setParent(ClassEntity parent) {
        return setParent(parent, true);
    }

    public ExtendsResult resetParent(ClassEntity parent, boolean updateParentMethods) {
        ExtendsResult result = new ExtendsResult(parent);

        if (this.parent == parent) {
            return result;
        }

        this.parent = parent;
        this.instanceOfList.clear();

        if (parent != null) {
            if (parent.useJavaLikeNames) {
                this.useJavaLikeNames = true;
            }

            this.methodCounts = parent.methodCounts + this.methods.size();

            inheritClassIdentity(parent);

            this.properties.putAll(parent.properties);
            this.staticProperties.putAll(parent.staticProperties);

            for (Map.Entry<String, ConstantEntity> entry : parent.constants.entrySet()) {
                if (!entry.getValue().isPrivate()) {
                    this.constants.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (updateParentMethods) {
            result.methods = updateParentBody();
        }

        return result;
    }

    public ExtendsResult setParent(ClassEntity parent, boolean updateParentMethods) {
        ExtendsResult result = new ExtendsResult(parent);

        if (this.parent != null) {
            throw new RuntimeException("Cannot re-assign parent for classes");
        }

        this.parent = parent;
        if (parent != null) {
            if (parent.useJavaLikeNames) {
                this.useJavaLikeNames = true;
            }

            this.methodCounts = parent.methodCounts;

            inheritClassIdentity(parent);

            this.properties.putAll(parent.properties);
            this.staticProperties.putAll(parent.staticProperties);

            for (Map.Entry<String, ConstantEntity> entry : parent.constants.entrySet()) {
                if (!entry.getValue().isPrivate()) {
                    this.constants.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (updateParentMethods) {
            result.methods = updateParentBody();
        }

        return result;
    }

    /**
     * return empty list if success else list with not valid implemented methods in class
     *
     * @param _interface
     * @return
     */
    public ImplementsResult addInterface(ClassEntity _interface) {
        ImplementsResult result = new ImplementsResult(_interface);

        for (ConstantEntity e : _interface.constants.values()) {
            ConstantEntity origin = constants.get(e.getName());
            if (hasPhp8Signatures()) {
                if (origin == null) constants.put(e.getName(), e);
                else if (origin != e) {
                    if (e.isFinal())
                        result.signature.addConstant(InvalidConstant.compile(InvalidConstant.Kind.FINAL, origin, e));
                    else if (origin.getClazz() != this)
                        result.signature.addConstant(InvalidConstant.compile(InvalidConstant.Kind.AMBIGUOUS, origin, e));
                    else if (!origin.isPublic())
                        result.signature.addConstant(InvalidConstant.compile(InvalidConstant.Kind.MUST_BE_PUBLIC, origin, e));
                }
            } else if (origin != null && e.getClazz().getId() == _interface.getId() &&
                    (parent == null || parent.constants.get(e.getName()) == null))
                result.signature.addConstant(InvalidConstant.error(InvalidConstant.Kind.INVALID_OVERRIDE, origin, e));
        }

        if (!hasPhp8Signatures()) this.constants.putAll(_interface.constants);

        String key = usesAsciiClassNames() ? PhpClassNames.canonicalKey(_interface.getName()) : _interface.getLowerName();
        this.interfaces.put(key, _interface);
        this.instanceOfList.add(key);
        this.instanceOfList.addAll(_interface.instanceOfList);

        for (MethodEntity method : _interface.getMethods().values()) {
            MethodEntity implMethod = findMethod(method.getLowerName());
            if (implMethod == method) continue;

            if (implMethod == null) {
                addMethod(method, null);
                if (type == Type.CLASS && !isAbstract)
                    result.signature.add(InvalidMethod.error(InvalidMethod.Kind.NON_EXISTS, method));
            } else {
                implMethod.setPrototype(method);

                if (!hasPhp8Signatures() && !implMethod.equalsBySignature(method)) {
                    result.signature.add(InvalidMethod.error(InvalidMethod.Kind.INVALID_SIGNATURE, implMethod));
                } else if (implMethod.isStatic() && !method.isStatic()) {
                    result.signature.add(InvalidMethod.error(InvalidMethod.Kind.MUST_NON_STATIC, implMethod));
                } else if (!implMethod.isStatic() && method.isStatic()) {
                    result.signature.add(InvalidMethod.error(InvalidMethod.Kind.MUST_STATIC, implMethod));
                }
            }
        }
        return result;
    }

    public Map<String, ClassEntity> getInterfaces() {
        return interfaces;
    }

    public void addTrait(ClassEntity trait) {
        if (!trait.isTrait())
            throw new IllegalArgumentException("'" + trait.getName() + "' is not a trait");

        this.traits.put(usesAsciiClassNames() ? PhpClassNames.canonicalKey(trait.getName()) : trait.getLowerName(), trait);
    }

    public boolean hasTrait(String traitLowerName) {
        return this.traits.containsKey(traitLowerName);
    }

    public Map<String, ClassEntity> getTraits() {
        return traits;
    }

    public Collection<ConstantEntity> getConstants() {
        return constants.values();
    }

    public Collection<PropertyEntity> getProperties() {
        return properties.values();
    }

    public Collection<PropertyEntity> getStaticProperties() {
        return staticProperties.values();
    }

    public SignatureResult addConstant(ConstantEntity constant) {
        if (hasPhp8Signatures()) constants.remove(constant.getName());
        constants.put(constant.getName(), constant);
        constant.setClazz(this);

        SignatureResult result = new SignatureResult();

        if (constant.isFinal() && constant.isPrivate())
            result.addConstant(InvalidConstant.compile(InvalidConstant.Kind.PRIVATE_FINAL, constant, null));
        if (isInterface() && !constant.isPublic()) {
            result.addConstant(hasPhp8Signatures()
                    ? InvalidConstant.compile(InvalidConstant.Kind.MUST_BE_PUBLIC_FOR_INTERFACE, constant, null)
                    : InvalidConstant.error(InvalidConstant.Kind.MUST_BE_PUBLIC_FOR_INTERFACE, constant, null));
        }

        return result;
    }

    public void addDynamicConstant(Environment env, String name, Memory value) {
        ConstantEntity entity = constants.get(name);
        env.getOrCreateStatic(entity.getInternalName(), value);
    }

    public void addDynamicStaticProperty(Environment env, String name, Memory value) {
        PropertyEntity prop = staticProperties.get(name);
        env.getOrCreateStatic(prop.getDynamicDefaultName(), value);
    }

    public void addDynamicProperty(Environment env, String name, Memory value) {
        PropertyEntity prop = properties.get(name);
        env.getOrCreateStatic(prop.getInternalName(), value);
    }

    public PropertyResult addProperty(PropertyEntity property) {
        PropertyResult result = new PropertyResult();
        PropertyEntity prototype = null;

        if (property.isStatic()) {
            prototype = staticProperties.get(property.getLowerName());
            if (prototype != null && prototype.getModifier() != property.getModifier()) {
                property.setPrototype(prototype);
            }

            if (prototype == null)
                prototype = properties.get(property.getName());

            staticProperties.put(property.getName(), property);
        } else {
            prototype = properties.get(property.getLowerName());
            if (prototype != null && prototype.getModifier() != property.getModifier()) {
                property.setPrototype(prototype);
            }

            if (prototype == null)
                prototype = staticProperties.get(property.getName());

            properties.put(property.getName(), property);
        }

        if (prototype != null) {
            if (property.getPrototype() != null) {
                if (prototype.isProtected() && property.isPrivate()) {
                    result.addError(InvalidProperty.Kind.MUST_BE_PROTECTED, property);
                }
                if (prototype.isPublic() && !property.isPublic()) {
                    result.addError(InvalidProperty.Kind.MUST_BE_PUBLIC, property);
                }
            }

            boolean overridden = property.modifier.ordinal() > prototype.modifier.ordinal();
            if (!property.isPrivate() && property.modifier == prototype.modifier)
                overridden = true;
            if (property.isPublic() && prototype.isProtected())
                overridden = true;

            if (prototype.isStatic() && !property.isStatic()) {
                if (overridden) {
                    property.setPrototype(prototype);
                    result.addError(InvalidProperty.Kind.STATIC_AS_NON_STATIC, property);
                }
            } else if (property.isStatic() && !prototype.isStatic()) {
                if (overridden) {
                    property.setPrototype(prototype);
                    result.addError(InvalidProperty.Kind.NON_STATIC_AS_STATIC, property);
                }
            }
        }

        property.setClazz(this);
        return result;
    }

    protected void addStaticProperty(PropertyEntity property) {
        if (!property.isStatic())
            throw new IllegalArgumentException("Property must be static");

        staticProperties.put(property.getLowerName(), property);
        property.setClazz(this);
    }

    public MethodEntity getConstructor() {
        return constructor;
    }

    public void setConstructor(MethodEntity constructor) {
        this.constructor = constructor;
        constructor.setClazz(this);
    }

    public DocumentComment getDocComment() {
        return docComment;
    }

    public void setDocComment(DocumentComment docComment) {
        this.docComment = docComment;
    }

    public Class<?> getNativeClass() {
        return nativeClazz;
    }

    @Deprecated
    public Class<?> getNativeClazz() {
        return nativeClazz;
    }

    protected static void invalidAccessToProperty(Environment env, TraceInfo trace, PropertyEntity entity, int accessFlag) {
        switch (accessFlag) {
            case 1:
                env.error(trace, ErrorType.E_ERROR,
                        Messages.ERR_ACCESS_TO_PROTECTED_PROPERTY.fetch(
                                entity.getClazz().getName(), entity.getName()
                        ));
            case 2:
                env.error(trace, ErrorType.E_ERROR,
                        Messages.ERR_ACCESS_TO_PRIVATE_PROPERTY.fetch(
                                entity.getClazz().getName(), entity.getName()
                        ));
        }
    }

    private void errorInvalidPropertyAccess(Environment env, TraceInfo trace, PropertyEntity entity, int accessFlag) {
        if ((accessFlag == 1 || accessFlag == 2) && usesModernPropertyPolicy(env, trace)) {
            String message = (accessFlag == 1 ? Messages.ERR_ACCESS_TO_PROTECTED_PROPERTY : Messages.ERR_ACCESS_TO_PRIVATE_PROPERTY)
                    .fetch(accessFlag == 1 || entity.isStatic() ? getName() : entity.getClazz().getName(), entity.getName());
            env.exception(trace, php.runtime.lang.exception.BaseError.class, message);
        }
        invalidAccessToProperty(env, trace, entity, accessFlag);
    }

    public void setNativeClazz(Class<?> nativeClazz) {
        this.nativeClazz = nativeClazz;

        if (nativeClazz.getAnnotation(Reflection.UseJavaLikeNames.class) != null) {
            useJavaLikeNames = true;
        }

        if (!nativeClazz.isInterface()) {
            try {
                this.nativeConstructor = nativeClazz.getConstructor(Environment.class, ClassEntity.class);
                this.nativeConstructor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                this.nativeConstructor = null;
                //if (IObject.class.isAssignableFrom(getNativeClass().getClass()))
                //  throw new CriticalException(e);
            }

            if (!this.isInternal) {
                try {
                    if (isTrait()) {
                        this.nativeInitEnvironment = nativeClazz.getDeclaredMethod(
                                "__$initEnvironment", Environment.class, String.class
                        );
                    } else {
                        this.nativeInitEnvironment = nativeClazz.getDeclaredMethod(
                                "__$initEnvironment", Environment.class
                        );
                    }
                    this.nativeInitEnvironment.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    this.nativeInitEnvironment = null;
                }
            }
        }
    }

    public ModuleEntity getModule() {
        return module;
    }

    public boolean usesAsciiClassNames() {
        return nativeAsciiClassIdentity || hasPhp8Signatures();
    }

    private boolean hasPhp8Signatures() {
        return module != null && module.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5;
    }

    @Override
    public void setName(String name) {
        super.setName(name);
        if (name != null && usesAsciiClassNames()) lowerName = PhpClassNames.canonicalKey(name);
    }

    public void setModule(ModuleEntity module) {
        this.module = module;
        if (name != null && usesAsciiClassNames()) lowerName = PhpClassNames.canonicalKey(name);
    }

    public void initEnvironment(Environment env) {
        if (deferredInitialization) {
            if (!isTrait()) for (TraitConstantCheck check : traitConstantChecks) checkTraitConstant(env, check);
            return;
        }
        initEnvironmentBody(env);
    }

    private void initEnvironmentBody(Environment env) {
        if (isTrait() && hasPhp8Signatures()) return; // Relative trait values belong to the composing class.
        if (isClass() && nativeInitEnvironment != null) {
            boolean scoped = hasPhp8Signatures();
            if (scoped) env.pushCall(getTrace(), null, null, "{constant-expression}", getName(), getName());
            try {
                nativeInitEnvironment.invoke(null, env);
            } catch (InvocationTargetException e) {
                env.__throwException(e);
            } catch (IllegalAccessException e) {
                throw new CriticalException(e);
            } finally {
                if (scoped) env.popCall();
            }
        }

        if (!traits.isEmpty()) {
            Set<ClassEntity> used = usesAsciiClassNames() ? Collections.newSetFromMap(new IdentityHashMap<>())
                    : new HashSet<ClassEntity>();
            try {
                for (ClassEntity trait : traits.values()) {
                    trait.initTraitEnvironment(env, this, used);
                    for (TraitConstantCheck check : traitConstantChecks)
                        if (check.trait.equals(trait.getName())) checkTraitConstant(env, check);
                }
            } catch (Exception e) {
                if (hasPhp8Signatures()) env.forwardThrow(e);
                else env.catchUncaught(e);
            } catch (Throwable e) {
                throw new CriticalException(e);
            }
        }
    }

    protected void initTraitEnvironment(Environment env, ClassEntity originClass, Set<ClassEntity> used) throws Throwable {
        initTraitEnvironment(env, originClass, used, getName());
    }

    private void initTraitEnvironment(Environment env, ClassEntity originClass, Set<ClassEntity> used, String composingTrait) throws Throwable {
        if (nativeInitEnvironment != null) {
            boolean scoped = hasPhp8Signatures();
            if (scoped) env.pushCall(getTrace(), null, null, composingTrait, originClass.getName(), originClass.getName());
            try {
                nativeInitEnvironment.invoke(null, env, originClass.getName());
            } catch (InvocationTargetException e) {
                env.__throwException(e);
            } finally {
                if (scoped) env.popCall();
            }
        }
        for (ClassEntity trait : traits.values()) {
            if (used.add(trait)) {
                trait.initTraitEnvironment(env, originClass, used, composingTrait);
            }
        }
        for (TraitConstantCheck check : traitConstantChecks)
            originClass.checkTraitConstant(env, check);
    }

    /** Explicit complete-provider route: no implicit class initialization or default evaluation. */
    public IObject createOwnershipShell(php.runtime.PhpOwnershipObjects.AllocationWitness witness, Environment env) throws Throwable {
        witness.consumeFactory(this,nativeClazz,nativeConstructor,env);
        try {return (IObject)nativeConstructor.newInstance(env,this);}
        catch (InvocationTargetException failure) {env.__throwException(failure);return null;}
    }

    public <T extends IObject> T newObjectWithoutConstruct(Environment env) {
        initializeRuntimeState(env);
        IObject object = null;
        try {
            if (nativeConstructor != null)
                object = (IObject) nativeConstructor.newInstance(env, this);
        } catch (InvocationTargetException e) {
            env.__throwException(e);
            return null;
        } catch (InstantiationException e) {
            throw new CriticalException(e);
        } catch (IllegalAccessException e) {
            throw new CriticalException(e);
        }

        if (object != null && hasPhp8Signatures()) initializeInstanceProperties(env, object);
        return (T) object;
    }

    public <T extends IObject> T newMock(Environment env) throws Throwable {
        if (nativeConstructor == null) {
            env.error(env.trace(), ErrorType.E_CORE_ERROR, "Cannot find a java constructor %s(Environment, ClassEntity)", getName());
        }

        try {
            IObject object = (IObject) nativeConstructor.newInstance(env, this);
            object.setAsMock();
            return (T) object;
        } catch (InstantiationException e) {
            return null;
        }
    }

    public void checkCanInstance(Environment env) {
        if (isAbstract) {
            env.error(trace, "Cannot instantiate abstract class %s", name);
        } else if (type == Type.INTERFACE) {
            env.error(trace, "Cannot instantiate interface %s", name);
        } else if (type == Type.TRAIT) {
            env.error(trace, "Cannot instantiate trait %s", name);
        }
    }

    public void checkCanInstance(Environment env, TraceInfo callTrace) {
        if (env.getModuleManager().getPhpLanguageProfile(callTrace) != PhpLanguageProfile.PHP_8_5) {
            checkCanInstance(env);
            return;
        }
        if (type == Type.INTERFACE) env.exception(callTrace, php.runtime.lang.exception.BaseError.class,
                "Cannot instantiate interface %s", name);
        else if (type == Type.TRAIT) env.exception(callTrace, php.runtime.lang.exception.BaseError.class,
                "Cannot instantiate trait %s", name);
        else if (isAbstract) env.exception(callTrace, php.runtime.lang.exception.BaseError.class,
                "Cannot instantiate abstract class %s", name);
    }

    public <T extends IObject> T newObject(Environment env, TraceInfo trace, boolean doConstruct, Memory... args)
            throws Throwable {
        return newObject(env, trace, doConstruct, true, args);
    }

    public <T extends IObject> T newObject(Environment env, TraceInfo trace, boolean doConstruct, boolean checks, Memory... args)
            throws Throwable {

        if (checks) {
            checkCanInstance(env, trace);
        }

        initializeRuntimeState(env);
        IObject object;
        try {
            if (nativeConstructor == null) {
                env.error(trace, ErrorType.E_CORE_ERROR, "Cannot find a java constructor %s(Environment, ClassEntity)", getName());
            }

            object = (IObject) nativeConstructor.newInstance(env, this);
        } catch (InvocationTargetException e) {
            env.__throwException(e);
            return null;
        }

        initializeInstanceProperties(env, object);

        if (doConstruct && methodConstruct != null) {
            ObjectInvokeHelper.invokeMethod(object, methodConstruct, env, trace, args, true);
        }

        return (T) object;
    }

    private void initializeInstanceProperties(Environment env, IObject object) {
        ArrayMemory props = object.getProperties();
        if (hasPhp8Signatures() && props instanceof php.runtime.memory.PhpObjectProperties) {
            for (ClassEntity owner = this; owner != null; owner = owner.parent)
                for (PropertyEntity property : owner.getProperties())
                    if (property.getGetter() == null)
                        ((php.runtime.memory.PhpObjectProperties) props).declare(property);
        }
        if (hasPhp8Signatures()) {
            // PHP retains inherited slot order, including slots overridden by a child.
            Deque<ClassEntity> lineage = new ArrayDeque<>();
            for (ClassEntity owner = this; owner != null; owner = owner.parent) lineage.push(owner);
            Set<String> initialized = new HashSet<>();
            while (!lineage.isEmpty()) {
                ClassEntity owner = lineage.pop();
                for (PropertyEntity declared : owner.getProperties()) {
                    if (declared.getClazz().getId() != owner.getId() || declared.getGetter() != null) continue;
                    PropertyEntity property = declared.isPrivate() ? declared : findProperty(declared.getName());
                    if (property == null || property.getGetter() != null || !initialized.add(property.getSpecificName())) continue;
                    props.putAsKeyString(property.getSpecificName(), property.getDefaultValue(env).toImmutable())
                            .attachPropertySource(env, property);
                }
            }
            return;
        }

        for (PropertyEntity property : getProperties()) {
            if (id == property.clazz.getId() && property.getGetter() == null) {
                props.putAsKeyString(
                        property.getSpecificName(),
                        property.getDefaultValue(env).toImmutable()
                ).attachPropertySource(env, property);
            }
        }

        ClassEntity tmp = parent;
        while (tmp != null) {
            long otherId = tmp.getId();
            for (PropertyEntity property : tmp.getProperties()) {
                if (property.getClazz().getId() == otherId && property.getGetter() == null) {
                    if (property.modifier != Modifier.PROTECTED || props.getByScalar(property.getName()) == null)
                        props.getByScalarOrCreate(
                                property.getSpecificName(),
                                property.getDefaultValue(env).toImmutable()
                        ).attachPropertySource(env, property);
                }
            }
            tmp = tmp.parent;
        }

    }

    public <T extends IObject> T cloneObject(T value, Environment env, TraceInfo trace) throws Throwable {
        php.runtime.PhpOwnershipObjects.rejectUnmanagedClone(value);
        // Keep virtual factory dispatch: specialized entities can reject cloning.
        IObject copy = this.newObjectWithoutConstruct(env);
        boolean modernStorage = hasPhp8Signatures()
                || env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5
                || value.getProperties() instanceof php.runtime.memory.PhpObjectProperties
                && ((php.runtime.memory.PhpObjectProperties) value.getProperties()).hasUnsetAnchors();
        if (modernStorage) {
            value.getProperties().enableBucketLayout();
            ArrayMemory defaults = copy.getProperties();
            ForeachIterator fresh = defaults.foreachIterator(false, false);
            while (fresh.next()) {
                ReferenceMemory cell = defaults.getByScalar(fresh.getKey());
                if (cell != null) cell.detachPropertySource();
            }
            // Copy the actual storage, including explicitly unset fields, rather than fresh defaults.
            defaults.clear();
        }
        ArrayMemory props = copy.getProperties();
        if (modernStorage && value.getProperties().hasBucketLayout()) {
            // Copy buckets, not keys: an older duplicate can carry a different canonical alias.
            for (int ordinal = 0; ordinal < value.getProperties().bucketLimit(); ordinal++) {
                ReferenceMemory source = value.getProperties().bucketAt(ordinal);
                if (source == null) { props.appendBucketHole(); continue; }
                Object key = value.getProperties().bucketKey(ordinal);
                ReferenceMemory target = props.insertBucket(key, source.getValue().isReference()
                        ? source.getValue() : source.getValue().toImmutable());
                if (value.getProperties() instanceof php.runtime.memory.PhpObjectProperties && key instanceof String) {
                    PropertyEntity declared = ((php.runtime.memory.PhpObjectProperties) value.getProperties()).declaration((String) key);
                    if (declared != null && !((php.runtime.memory.PhpObjectProperties) value.getProperties()).isExplicitlyUnset((String) key))
                        target.attachPropertySource(env, declared);
                }
            }
            if (props instanceof php.runtime.memory.PhpObjectProperties && value.getProperties() instanceof php.runtime.memory.PhpObjectProperties)
                ((php.runtime.memory.PhpObjectProperties) props).copyMetadataFrom((php.runtime.memory.PhpObjectProperties) value.getProperties());
        }
        ForeachIterator iterator = value.getProperties().foreachIterator(false, false);
        while (!(modernStorage && value.getProperties().hasBucketLayout()) && iterator.next()) {
            Object key = iterator.getKey();
            if (key instanceof String && (hasPhp8Signatures()
                    || env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)) {
                ReferenceMemory source=value.getProperties().getByScalar((String)key);
                ReferenceMemory target=(ReferenceMemory)props.refOfIndex((String)key);
                if (source.getValue().isReference()) target.assignRef(source.getValue());
                else target.assign(source.getValue().toImmutable());
                PropertyEntity stored=null;
                for (ClassEntity owner=this;owner != null && stored == null;owner=owner.parent)
                    for (PropertyEntity candidate : owner.properties.values())
                        if (candidate.getSpecificName().equals(key)) {stored=candidate;break;}
                if (stored != null) target.attachPropertySource(env,stored);
                continue;
            }
            if (key instanceof String) {
                String name = (String) key;
                if (name.indexOf('\0') > -1)
                    name = name.substring(name.lastIndexOf('\0') + 1);

                PropertyEntity entity = properties.get(name);
                if (entity != null) {
                    if (props.getByScalar(entity.getSpecificName()) == null)
                        props.put(entity.getSpecificName(), iterator.getValue().toImmutable());
                } else
                    props.put(key, iterator.getValue().toImmutable());
            } else
                props.put(key, iterator.getValue().toImmutable());
        }

        if (methodMagicClone != null) {
            ObjectInvokeHelper.invokeMethod(copy, methodMagicClone, env, trace, null, true);
        }

        return (T) copy;
    }

    /** Keep checked modern arithmetic inside the property's access and creation transaction. */
    public Memory assignArithmeticProperty(Environment env, TraceInfo trace, IObject object, String property,
                                           Memory right, String operator, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, right, new SetterCallback() {
            @Override
            public Memory invoke(Memory left, Memory value) {
                return php.runtime.PhpArithmetic.binary(left, value, operator, env, trace);
            }
        }, callCache, cacheIndex);
    }

    public Memory incrementProperty(Environment env, TraceInfo trace, IObject object, String property,
                                    boolean decrement, boolean post, PropertyCallCache callCache, int cacheIndex) throws Throwable {
        PropertyEntity nativeField = findCachedInstanceProperty(env, trace, property, callCache, cacheIndex);
        if (nativeField instanceof CompilePropertyEntity) {
            ReferenceMemory target = new ReferenceMemory(getProperty(env, trace, object, property, callCache, cacheIndex).toImmutable());
            return php.runtime.PhpArithmetic.incrementReference(target, decrement, post, env, trace,
                    updated -> nativeField.assignValue(env, trace, object, property, updated));
        }
        final Memory[] result = {Memory.NULL};
        setProperty(env, trace, object, property, Memory.NULL, new SetterCallback() {
            @Override public boolean assignsTarget() { return true; }
            @Override public boolean incrementDecrement() { return true; }
            @Override public Memory invoke(Memory left, Memory ignored) {
                Memory before = left.toValue().toImmutable();
                Memory updated = php.runtime.PhpArithmetic.increment(before, decrement, env, trace);
                result[0] = post ? before : updated;
                return updated;
            }
            @Override public Memory invokeTarget(ReferenceMemory target, Memory ignored) {
                boolean shared = target.isSharedIncrementCell();
                PropertyEntity declared = findCachedInstanceProperty(env, trace, property, callCache, cacheIndex);
                String key = declared != null && object.getProperties().getByScalar(declared.getSpecificName()) == target
                        ? declared.getSpecificName() : property;
                result[0] = php.runtime.PhpArithmetic.incrementReference(target, decrement, post, env, trace, value -> {
                    if (!shared && object.getProperties().getByScalar(key) != target) {
                        target.detachPropertySource();
                        ReferenceMemory replacement;
                        if (declared != null && key.equals(declared.getSpecificName())
                                && object.getProperties() instanceof php.runtime.memory.PhpObjectProperties) {
                            replacement = ((php.runtime.memory.PhpObjectProperties) object.getProperties()).republishDeclared(key, value);
                        } else {
                            object.getProperties().removeByScalar(key);
                            replacement = object.getProperties().putAsKeyString(key, value);
                        }
                        if (declared != null && key.equals(declared.getSpecificName())) replacement.attachPropertySource(env, declared);
                    }
                });
                return target.toValue();
            }
            @Override public Memory afterWarningFailure(ReferenceMemory target, Memory ignored) {
                // A fresh writable fetch still commits, but does not issue further operator diagnostics.
                Memory updated = decrement ? Memory.NULL : Memory.CONST_INT_1;
                target.assign(updated); result[0] = post ? Memory.NULL : updated;
                return updated;
            }
        }, callCache, cacheIndex);
        return result[0];
    }

    public Memory concatProperty(Environment env, TraceInfo trace,
                                 IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return new StringMemory(o1.concat(o2));
            }
        }, callCache, cacheIndex);
    }

    public Memory plusProperty(Environment env, TraceInfo trace,
                               IObject object, String property, Memory memory, final ReferenceMemory oldValue)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                if (oldValue != null)
                    oldValue.assign(o1);

                return o1.plus(o2);
            }
        }, null, 0);
    }

    public Memory minusProperty(Environment env, TraceInfo trace,
                                IObject object, String property, Memory memory, final ReferenceMemory oldValue)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                if (oldValue != null)
                    oldValue.assign(o1);

                return o1.minus(o2);
            }
        }, null, 0);
    }

    public Memory mulProperty(Environment env, TraceInfo trace,
                              IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return o1.mul(o2);
            }
        }, callCache, cacheIndex);
    }

    public Memory divProperty(Environment env, TraceInfo trace,
                              IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return o1.div(o2);
            }
        }, callCache, cacheIndex);
    }

    public Memory modProperty(Environment env, TraceInfo trace,
                              IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return o1.mod(o2);
            }
        }, callCache, cacheIndex);
    }

    public Memory bitAndProperty(Environment env, TraceInfo trace,
                                 IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return o1.bitAnd(o2);
            }
        }, callCache, cacheIndex);
    }

    public Memory bitOrProperty(Environment env, TraceInfo trace,
                                IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return o1.bitOr(o2);
            }
        }, callCache, cacheIndex);
    }

    public Memory bitXorProperty(Environment env, TraceInfo trace,
                                 IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return o1.bitXor(o2);
            }
        }, callCache, cacheIndex);
    }

    public Memory bitShrProperty(Environment env, TraceInfo trace,
                                 IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return o1.bitShr(o2);
            }
        }, callCache, cacheIndex);
    }

    public Memory bitShlProperty(Environment env, TraceInfo trace,
                                 IObject object, String property, Memory memory, PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return setProperty(env, trace, object, property, memory, new SetterCallback() {
            @Override
            public Memory invoke(Memory o1, Memory o2) {
                return o1.bitShl(o2);
            }
        }, callCache, cacheIndex);
    }

    /** Check a real object-storage insertion, after magic dispatch and key normalization. */
    public void checkDynamicPropertyCreation(Environment env, TraceInfo trace, ArrayMemory props, String storageKey) {
        if (requiresDynamicPropertyNotice(env, trace, props, storageKey)) {
            dynamicPropertyNotice(env, trace, storageKey);
        }
    }

    /** Serialized declared static names are restored as instance slots without a creation notice. */
    public void checkDynamicPropertyRestoration(Environment env, TraceInfo trace, ArrayMemory props, String storageKey) {
        if (findStaticProperty(storageKey) == null) checkDynamicPropertyCreation(env, trace, props, storageKey);
    }

    private boolean usesModernPropertyPolicy(Environment env, TraceInfo trace) {
        var caller = php.runtime.PhpPropertyWriteContext.current(env);
        return hasPhp8Signatures() || env.getModuleManager().getPhpLanguageProfile(caller == null ? trace : caller.trace)
                == PhpLanguageProfile.PHP_8_5;
    }

    private boolean requiresDynamicPropertyNotice(Environment env, TraceInfo trace, ArrayMemory props, String storageKey) {
        if (props == null || props.getByScalar(storageKey) != null) return false;
        if (!usesModernPropertyPolicy(env, trace)) return false;
        String basename = dynamicPropertyLabel(storageKey);
        for (ClassEntity owner = this; owner != null; owner = owner.parent) {
            PropertyEntity property = owner.properties.get(basename);
            if (property != null && !property.isStatic() && property.getSpecificName().equals(storageKey)) return false;
        }
        checkProhibitedDynamicProperty(env, trace, storageKey);
        return !isInstanceOfAsciiName("stdClass");
    }

    private void checkProhibitedDynamicProperty(Environment env, TraceInfo trace, String storageKey) {
        if (usesModernPropertyPolicy(env, trace) && (type == Type.CLOSURE || type == Type.GENERATOR
                || isInstanceOfAsciiName("Closure") || isInstanceOfAsciiName("Generator"))) {
            env.exception(trace, php.runtime.lang.exception.BaseError.class,
                    "Cannot create dynamic property %s::$%s", getName(), dynamicPropertyLabel(storageKey));
        }
    }

    private static String dynamicPropertyLabel(String storageKey) {
        if (storageKey.startsWith("\0")) {
            int scopeEnd = storageKey.indexOf('\0', 1);
            if (scopeEnd >= 0) return storageKey.substring(scopeEnd + 1);
        }
        return storageKey;
    }

    private void dynamicPropertyNotice(Environment env, TraceInfo trace, String storageKey) {
        env.error(trace, ErrorType.E_DEPRECATED, "Creation of dynamic property %s::$%s is deprecated",
                getName(), dynamicPropertyLabel(storageKey));
    }

    private Memory createDynamicProperty(Environment env, TraceInfo trace, ArrayMemory props, String storageKey,
                                         Memory initial, boolean referencePreparation, SetterCallback operation) throws Throwable {
        // Prohibitions must fail before mutation; a thrown deprecation handler has different semantics.
        boolean notice = requiresDynamicPropertyNotice(env, trace, props, storageKey);
        if (!notice && (operation == null || !usesModernPropertyPolicy(env, trace))) {
            if (operation != null) initial = operation.invoke(Memory.NULL, initial);
            if (usesModernPropertyPolicy(env, trace)) {
                ReferenceMemory created = props.getByScalarOrCreate(storageKey, Memory.NULL);
                return referencePreparation ? created : created.assign(initial);
            }
            return referencePreparation ? props.refOfIndex(storageKey) : props.refOfIndex(storageKey).assign(initial);
        }
        Throwable warningFailure = null;
        try {
            if (notice) dynamicPropertyNotice(env, trace, storageKey);
            if (operation != null) env.error(trace, ErrorType.E_WARNING, Messages.ERR_UNDEFINED_PROPERTY, getName(), storageKey);
        } catch (Throwable failure) {
            warningFailure = failure;
        }
        ReferenceMemory created = props.getByScalar(storageKey);
        if (warningFailure != null && created != null && operation != null && operation.incrementDecrement())
            throw warningFailure;
        if (created == null || !referencePreparation && operation == null) {
            // A reentrant plain setter appends a second physical bucket. Older aliases and
            // warmed property offsets keep the older bucket; a cold hash lookup sees this one.
            created = created != null ? props.insertBucket(storageKey, initial)
                    : props.putAsKeyString(storageKey, operation == null ? initial : Memory.NULL);
        }
        if (operation != null) {
            // A failed compound operator leaves the newly created NULL slot in place.
            initial = warningFailure != null && operation.incrementDecrement()
                    ? operation.afterWarningFailure(created, initial) : operation.invokeTarget(created, initial);
            if (!operation.assignsTarget()) created.assign(initial);
        }
        if (warningFailure != null) throw warningFailure;
        return referencePreparation ? created : initial;
    }

    public void appendProperty(IObject object, String property, Memory value) {
        object.getProperties().put(property, value);
    }

    public Memory refOfProperty(ArrayMemory props, String name) {
        PropertyEntity entity = properties.get(name);
        return props.refOfIndex(entity == null ? name : entity.getSpecificName());
    }

    public Memory setProperty(Environment env, TraceInfo trace,
                              IObject object, String property, Memory memory, SetterCallback callback,
                              PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        ReferenceMemory value;

        PropertyEntity entity = findCachedInstanceProperty(env, trace, property, callCache, cacheIndex);

        if (entity == null) checkProhibitedDynamicProperty(env, trace, property);

        if (entity == null) {
            PropertyEntity staticEntity = staticProperties.get(property);
            if (staticEntity != null) {
                errorInvalidPropertyAccess(env, trace, staticEntity, staticEntity.canAccess(env));
                env.error(trace, usesModernPropertyPolicy(env, trace) ? ErrorType.E_NOTICE : ErrorType.E_STRICT,
                        Messages.ERR_ACCESSING_STATIC_PROPERTY_AS_NON_STATIC,
                        staticEntity.getClazz().getName(),
                        staticEntity.getName()
                );
            }
        }

        int accessFlag = entity == null ? 0 : entity.canAccess(env);

        ArrayMemory props = object.getProperties();

        if (entity != null) {
            if (entity.setter != null) {
                if (callback != null)
                    memory = callback.invoke(getProperty(env, trace, object, property, null, 0), memory);

                try {
                    ObjectInvokeHelper.invokeMethod(object, entity.setter, env, trace, new Memory[]{memory}, false);
                } catch (IllegalArgumentException e) {
                    if (!object.getReflection().isInstanceOf(entity.setter.getClazz())) {
                        return setProperty(env, trace, object, property, memory, callback, null, 0);
                    }

                    throw e;
                }
                return memory;
            } else if (entity.getter != null) {
                env.error(trace, ErrorType.E_RECOVERABLE_ERROR, Messages.ERR_READONLY_PROPERTY.fetch(entity.getClazz().getName(), property));
            }
        }

        value = props == null || accessFlag != 0 ? null : entity == null
                ? findDynamicSlot(env, trace, props, property, callCache, cacheIndex) : props.getByScalar(entity.specificName);

        if (value == null) {
            boolean recursive = false;

            ClassEntity context = env.getLastClassOnStack();

            if (context != null && methodMagicSet != null && context.getId() == methodMagicSet.getClazz().getId()) {
                recursive = env.peekCall(0).flags == FLAG_SET;
            }

            if (methodMagicSet != null && !recursive) {
                if (accessFlag != 0 && callback != null && methodMagicGet == null && usesModernPropertyPolicy(env, trace))
                    errorInvalidPropertyAccess(env, trace, entity, accessFlag);
                StringMemory memoryProperty = new StringMemory(property);

                if (callback != null) {
                    Memory o1 = Memory.NULL;
                    if (methodMagicGet != null) {
                        try {
                            Memory[] args = new Memory[]{ memoryProperty };
                            env.pushCall(
                                    trace, object, args, methodMagicGet.getName(), methodMagicSet.getClazz().getName(), name
                            );
                            env.peekCall(0).flags = FLAG_GET;

                            args = InvokeArgumentHelper.checkType(env, trace, methodMagicGet, args);
                            o1 = methodMagicGet.invokeDynamic(object, env, trace, args);
                        } finally {
                            env.popCall();
                        }
                    }
                    memory = callback.invoke(o1, memory);
                }

                try {
                    Memory[] args = new Memory[]{memoryProperty, memory};
                    env.pushCall(trace, object, args, methodMagicSet.getName(), methodMagicSet.getClazz().getName(), name);
                    env.peekCall(0).flags = FLAG_SET;

                    args = InvokeArgumentHelper.checkType(env, trace, methodMagicSet, args);
                    methodMagicSet.invokeDynamic(object, env, trace, args);
                } finally {
                    env.popCall();
                }
            } else {
                if (accessFlag != 0 && usesModernPropertyPolicy(env, trace)) {
                    // A getter-only compound reads and computes before its forbidden write.
                    if (callback != null && methodMagicGet != null)
                        memory = callback.invoke(getProperty(env, trace, object, property, callCache, cacheIndex), memory);
                    errorInvalidPropertyAccess(env, trace, entity, accessFlag);
                }
                /*if (accessFlag != 0) {
                    errorInvalidPropertyAccess(env, trace, entity, accessFlag);
                    return Memory.NULL;
                }*/

                // Preserve the legacy callback/access ordering; modern creation owns its callback timing.
                if (callback != null && !usesModernPropertyPolicy(env, trace)) {
                    if (entity != null) entity.readInitialized(env, trace, null);
                    memory = callback.invoke(Memory.NULL, memory);
                    callback = null;
                }

                String name = property;
                if (entity != null) {
                    if (accessFlag != 0 && context == null) {
                        switch (accessFlag) {
                            case 2:
                                if (object.getReflection().getId() == entity.getClazz().getId()) {
                                    errorInvalidPropertyAccess(env, trace, entity, accessFlag);
                                    return Memory.NULL;
                                }
                                break;
                            case 1:
                                errorInvalidPropertyAccess(env, trace, entity, accessFlag);
                                return Memory.NULL;
                        }
                    }

                    if (context != null) {
                        switch (entity.modifier) {
                            case PRIVATE:
                                if (entity.getClazz().getId() == context.getId())
                                    name = entity.specificName;
                                break;
                            case PROTECTED:
                                if (context.isInstanceOf(entity.getClazz()))
                                    name = entity.specificName;
                        }
                    }
                }

                if (callback != null && methodMagicGet != null && usesModernPropertyPolicy(env, trace)) {
                    memory = callback.invoke(getProperty(env, trace, object, property, callCache, cacheIndex), memory);
                    callback = null;
                }
                if (props != null && (entity == null || usesModernPropertyPolicy(env, trace) && !name.equals(entity.getSpecificName()))) {
                    return createDynamicProperty(env, trace, props, name, memory, false, callback);
                }
                if (callback != null) {
                    if (entity != null) entity.readInitialized(env, trace, null);
                    memory = callback.invoke(Memory.NULL, memory);
                }
                return props == null ? Memory.NULL : entity.assignValue(env, trace, object, name, memory);
            }
        } else {
            if (callback != null) {
                if (entity != null) entity.readInitialized(env,trace,value);
                memory = callback.invokeTarget(value, memory);
                if (callback.assignsTarget()) return memory;
            }

            if (entity instanceof CompilePropertyEntity) {
                return entity.assignValue(env, trace, object, property, memory);
            }

            return value.assign(memory);
        }
        return memory;
    }

    public Memory unsetProperty(Environment env, TraceInfo trace, IObject object, String property,
                                PropertyCallCache callCache, int index)
            throws Throwable {
        ClassEntity context = env.getLastClassOnStack();
        PropertyEntity entity = findCachedInstanceProperty(env, trace, property, callCache, index);

        int accessFlag = entity == null ? 0 : entity.canAccess(env);

        if (entity == null) {
            PropertyEntity staticEntity = staticProperties.get(property);
            if (staticEntity != null) {
                errorInvalidPropertyAccess(env, trace, staticEntity, staticEntity.canAccess(env));
            }
        }

        ArrayMemory props = object.getProperties();
        Memory removed=null;
        if (props != null && accessFlag == 0) {
            if (entity != null && usesModernPropertyPolicy(env, trace)
                    && props instanceof php.runtime.memory.PhpObjectProperties) {
                ((php.runtime.memory.PhpObjectProperties) props).declare(entity);
                if (((php.runtime.memory.PhpObjectProperties) props).unsetDeclared(entity.specificName)) removed = Memory.TRUE;
            } else {
                removed=props.removeByScalar(entity == null ? property : entity.specificName);
                if (removed instanceof ReferenceMemory) ((ReferenceMemory)removed).detachPropertySource();
            }
        }
        if (props == null || accessFlag != 0 || removed == null) {
            if (methodMagicUnset != null) {
                if (context != null && context.getId() == methodMagicUnset.getClazz().getId()) {
                    if (env.peekCall(0).flags == FLAG_UNSET) {
                        return Memory.NULL;
                    }
                }

                try {
                    Memory[] args = new Memory[]{new StringMemory(property)};
                    env.pushCall(trace, object, args, methodMagicUnset.getName(), methodMagicUnset.getClazz().getName(), name);
                    env.peekCall(0).flags = FLAG_UNSET;

                    args = InvokeArgumentHelper.checkType(env, trace, methodMagicUnset, args);
                    methodMagicUnset.invokeDynamic(object, env, trace, args);
                } finally {
                    env.popCall();
                }
                return Memory.NULL;
            }
        }

        if (accessFlag != 0)
            errorInvalidPropertyAccess(env, trace, entity, accessFlag);

        entity = staticProperties.get(property);
        if (entity != null) {
            env.error(trace, ErrorType.E_STRICT,
                    Messages.ERR_ACCESSING_STATIC_PROPERTY_AS_NON_STATIC,
                    entity.getClazz().getName(),
                    entity.getName()
            );
        }

        return Memory.NULL;
    }

    public Memory emptyProperty(Environment env, TraceInfo trace, IObject object, String property)
            throws Throwable {
        ClassEntity contex = env.getLastClassOnStack();
        PropertyEntity entity = findCachedInstanceProperty(env, trace, property, null, 0);

        int accessFlag = entity == null ? 0 : entity.canAccess(env);

        ArrayMemory props = object.getProperties();
        if (props != null && accessFlag == 0) {
            Memory tmp = entity == null ? props.getByScalar(property) : getInstanceValue(env, trace, object, entity);
            if (tmp != null) {
                return tmp.toBoolean() ? Memory.TRUE : Memory.NULL;
            } else if (!usesModernPropertyPolicy(env, trace)
                    && (!(props instanceof php.runtime.memory.PhpObjectProperties)
                    || !((php.runtime.memory.PhpObjectProperties) props).hasUnsetAnchors()))
                return Memory.NULL;
        }

        if (methodMagicIsset != null) {
            Memory result;
            if (contex != null && contex.getId() == methodMagicIsset.getClazz().getId()) {
                if (env.peekCall(0).flags == FLAG_ISSET) {
                    return object.getProperties().getByScalar(property) != null ? Memory.TRUE : Memory.NULL;
                }
            }
            try {
                Memory[] args = new Memory[]{new StringMemory(property)};
                env.pushCall(
                        trace, object, args, methodMagicIsset.getName(), methodMagicIsset.getClazz().getName(), name
                );
                env.peekCall(0).flags = FLAG_ISSET;

                InvokeArgumentHelper.checkType(env, trace, methodMagicIsset, new StringMemory(property));
                result = methodMagicIsset.invokeDynamic(object, env, trace, new StringMemory(property))
                        .toBoolean() ? Memory.TRUE : Memory.NULL;
            } finally {
                env.popCall();
            }
            if (result.toBoolean() && methodMagicGet != null && usesModernPropertyPolicy(env, trace))
                return getProperty(env, trace, object, property, null, 0).toBoolean() ? Memory.TRUE : Memory.NULL;
            return result;
        }
        return Memory.NULL;
    }

    public Memory issetProperty(Environment env, TraceInfo trace, IObject object, String property,
                                PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        PropertyEntity entity = findCachedInstanceProperty(env, trace, property, callCache, cacheIndex);

        int accessFlag = entity == null ? 0 : entity.canAccess(env);

        ArrayMemory props = object.getProperties();
        Memory tmp = null;

        if (props != null && accessFlag == 0) {
            tmp = entity == null ? props.getByScalar(property) : getInstanceValue(env, trace, object, entity);
        }

        if (tmp != null) {
            return tmp.isNull() ? tmp : Memory.TRUE;
        }

        if (methodMagicIsset != null) return invokeMagicIsset(env, trace, object, property);

        return Memory.NULL;
    }

    private Memory invokeMagicIsset(Environment env, TraceInfo trace, IObject object, String property) throws Throwable {
        Memory result;

        ClassEntity context = env.getLastClassOnStack();

        if (context != null && context.getId() == methodMagicIsset.getClazz().getId())
            if (env.peekCall(0).flags == FLAG_ISSET) {
                return object.getProperties().getByScalar(property) != null ? Memory.TRUE : Memory.NULL;
            }

        try {
            Memory[] args = new Memory[]{ new StringMemory(property) };

            env.pushCall(trace, object, args, methodMagicIsset.getName(), methodMagicIsset.getClazz().getName(), name);
            env.peekCall(0).flags = FLAG_ISSET;

            args = InvokeArgumentHelper.checkType(env, trace, methodMagicIsset, args);
            result = methodMagicIsset.invokeDynamic(object, env, trace, args)
                    .toBoolean() ? Memory.TRUE : Memory.NULL;
        } finally {
            env.popCall();
        }
        return result;
    }

    public Memory getStaticProperty(Environment env, TraceInfo trace, String property, boolean errorIfNotExists,
                                    boolean checkAccess, ClassEntity context, PropertyCallCache callCache, int cacheIndex,
                                    boolean lateStaticCall)
            throws Throwable {
        PropertyEntity entity = callCache == null || env instanceof ConcurrentEnvironment || context != null ? null : callCache.get(env, cacheIndex);

        if (entity == null) {
            boolean saveCache = context == null && callCache != null;

            context = context == null ? env.getLastClassOnStack() : context;
            entity = isInstanceOf(context) ? context.findStaticProperty(property) : findStaticProperty(property);

            if (saveCache && entity != null) {
                callCache.put(env, cacheIndex, entity);
            }
        }

        if (entity == null) {
            if (errorIfNotExists)
                env.error(trace, Messages.ERR_ACCESS_TO_UNDECLARED_STATIC_PROPERTY.fetch(name, property));
            return Memory.NULL;
        }

        if (checkAccess) {
            int accessFlag = entity.canAccess(env, lateStaticCall ? context : null);
            if (accessFlag != 0) {
                if (!errorIfNotExists && usesModernPropertyPolicy(env, trace)) return Memory.NULL;
                errorInvalidPropertyAccess(env, trace, entity, accessFlag);
                return Memory.NULL;
            }
        }

        initializeRuntimeState(env);
        return entity.getStaticValue(env, trace);
    }

    public Memory getRefProperty(Environment env, TraceInfo trace, IObject object, String property,
                                 PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        Memory value;

        PropertyEntity entity = findCachedInstanceProperty(env, trace, property, callCache, cacheIndex);

        if (entity == null) checkProhibitedDynamicProperty(env, trace, property);

        if (entity == null) {
            PropertyEntity staticEntity = staticProperties.get(property);
            if (staticEntity != null) {
                errorInvalidPropertyAccess(env, trace, staticEntity, staticEntity.canAccess(env));
                env.error(trace, usesModernPropertyPolicy(env, trace) ? ErrorType.E_NOTICE : ErrorType.E_STRICT,
                        Messages.ERR_ACCESSING_STATIC_PROPERTY_AS_NON_STATIC,
                        staticEntity.getClazz().getName(),
                        staticEntity.getName()
                );
            }
        }

        int accessFlag = entity == null ? 0 : entity.canAccess(env);

        ArrayMemory props = object.getProperties();
        value = props == null || accessFlag != 0 ? null : entity == null
                ? findDynamicSlot(env, trace, props, property, callCache, cacheIndex) : props.getByScalar(entity.specificName);

        if (accessFlag != 0 && (methodMagicGet == null
                || !usesModernPropertyPolicy(env, trace))) {
            errorInvalidPropertyAccess(env, trace, entity, accessFlag);
        }
        if (value instanceof ReferenceMemory && entity != null)
            ((ReferenceMemory)value).attachPropertySource(env, entity);

        if (value == null) {
            if (methodMagicGet != null) {
                Memory altResult = getProperty(env, trace, object, property, callCache, cacheIndex, true);

                if (methodMagicGet.isReturnReference() && altResult.isReference()
                        && usesModernPropertyPolicy(env, trace))
                    return altResult;
                if (altResult.isObject()) {
                    return altResult;
                }
                if (usesModernPropertyPolicy(env, trace)) {
                    if (entity == null || accessFlag != 0 || entity.getDeclaredType() == null)
                        env.error(trace, ErrorType.E_NOTICE, Messages.ERR_INDIRECT_MODIFICATION_OVERLOADED_PROPERTY, name, property);
                    return new ReferenceMemory(altResult.toImmutable());
                }
            }

            value = props == null ? new ReferenceMemory() : entity == null
                    ? createDynamicProperty(env, trace, props, property, Memory.NULL, true, null)
                    : props.refOfIndex(entity.getSpecificName());
            if (entity != null && entity.getDeclaredType() != null && value instanceof ReferenceMemory) {
                ((ReferenceMemory)value).unset();
                ((ReferenceMemory)value).attachPropertySource(env, entity);
            }

            if (methodMagicGet != null || methodMagicSet != null) {
                env.error(trace,
                        props == null ? ErrorType.E_ERROR : ErrorType.E_NOTICE,
                        Messages.ERR_INDIRECT_MODIFICATION_OVERLOADED_PROPERTY, name, property);
            }
        }

        return value;
    }

    public Memory getProperty(Environment env, TraceInfo trace, IObject object, String property,
                              PropertyCallCache callCache, int cacheIndex) throws Throwable {
        return getProperty(env,trace,object,property,callCache,cacheIndex,false);
    }

    /** Quiet read used by coalescing; unlike isset, a getter without __isset may supply a value. */
    public Memory getQuietProperty(Environment env, TraceInfo trace, IObject object, String property,
                                   PropertyCallCache callCache, int cacheIndex) throws Throwable {
        if (!usesModernPropertyPolicy(env, trace))
            return issetProperty(env, trace, object, property, callCache, cacheIndex).isNull() ? Memory.NULL
                    : getProperty(env, trace, object, property, callCache, cacheIndex);
        return getProperty(env, trace, object, property, callCache, cacheIndex, false, null, true);
    }

    public Memory getReflectedProperty(Environment env, TraceInfo trace, IObject object, PropertyEntity property) throws Throwable {
        return getProperty(env, trace, object, property.getName(), null, 0, false, property);
    }

    private Memory getProperty(Environment env, TraceInfo trace, IObject object, String property,
                               PropertyCallCache callCache, int cacheIndex, boolean referenceRead) throws Throwable {
        return getProperty(env, trace, object, property, callCache, cacheIndex, referenceRead, null);
    }

    private Memory getProperty(Environment env, TraceInfo trace, IObject object, String property,
                               PropertyCallCache callCache, int cacheIndex, boolean referenceRead, PropertyEntity reflected) throws Throwable {
        return getProperty(env, trace, object, property, callCache, cacheIndex, referenceRead, reflected, false);
    }

    private Memory getProperty(Environment env, TraceInfo trace, IObject object, String property,
                               PropertyCallCache callCache, int cacheIndex, boolean referenceRead, PropertyEntity reflected, boolean quietRead) throws Throwable {
        Memory value;

        PropertyEntity entity = reflected == null ? findCachedInstanceProperty(env, trace, property, callCache, cacheIndex) : reflected;

        if (entity == null) {
            PropertyEntity staticEntity = staticProperties.get(property);

            if (staticEntity != null && !quietRead) {
                errorInvalidPropertyAccess(env, trace, staticEntity, staticEntity.canAccess(env));
                env.error(trace, usesModernPropertyPolicy(env, trace) ? ErrorType.E_NOTICE : ErrorType.E_STRICT,
                        Messages.ERR_ACCESSING_STATIC_PROPERTY_AS_NON_STATIC,
                        staticEntity.getClazz().getName(),
                        staticEntity.getName()
                );
            }
        }

        int accessFlag = reflected != null || entity == null ? 0 : entity.canAccess(env);

        if (entity != null && accessFlag != 0) {
            value = null;
        } else {
            if (entity != null) {
                value = getInstanceValue(env, trace, object, entity);
            } else {
                ArrayMemory props = object.getProperties();
                value = findDynamicSlot(env, trace, props, property, callCache, cacheIndex);
            }
        }

        if (value != null) {
            if (quietRead && value.isUndefined()) return Memory.NULL;
            if (entity != null) entity.readInitialized(env, trace, value);
            return value;
        }

        if (quietRead && methodMagicIsset != null && invokeMagicIsset(env, trace, object, property).isNull())
            return Memory.NULL;

        if (methodMagicGet != null) {
            Memory result;

            ClassEntity context = env.getLastClassOnStack();

            if (context != null && context.getId() == methodMagicGet.getClazz().getId()) {
                if (env.peekCall(0).flags == FLAG_GET) {
                    if (!quietRead) env.error(trace, usesModernPropertyPolicy(env, trace) ? ErrorType.E_WARNING : ErrorType.E_NOTICE,
                            Messages.ERR_UNDEFINED_PROPERTY, getName(), property);
                    return Memory.NULL;
                }
            }

            try {
                Memory[] args = new Memory[]{new StringMemory(property)};
                env.pushCall(trace, object, args, methodMagicGet.getName(), methodMagicGet.getClazz().getName(), name);
                env.peekCall(0).flags = FLAG_GET;

                InvokeArgumentHelper.checkType(env, trace, methodMagicGet, args);
                result = methodMagicGet.invokeDynamic(object, env, trace, args);
                if (entity != null && accessFlag == 0 && entity.getDeclaredType() != null) {
                    if (referenceRead && !methodMagicGet.isReturnReference())
                        env.error(trace,ErrorType.E_NOTICE,Messages.ERR_INDIRECT_MODIFICATION_OVERLOADED_PROPERTY,name,property);
                    var module=methodMagicGet.getClazz().getModule();
                    var writeContext=php.runtime.PhpPropertyWriteContext.enter(env,trace,module != null && module.isStrictTypes());
                    try {
                        var source=new php.runtime.memory.helper.PhpPropertyGuard.Source(env,entity);
                        Memory checked=source.rawCoerce(result.toValue());
                        if (checked == null) env.exception(trace,php.runtime.lang.exception.BaseTypeError.class,
                                "Value of type %s returned from %s::__get() must be compatible with unset property %s of type %s",
                                php.runtime.reflection.support.PhpTypeDeclaration.valueType(result),methodMagicGet.getClazz().getName(),
                                source.label(),source.type().getErrorSignature());
                        if (methodMagicGet.isReturnReference() && result instanceof ReferenceMemory) {
                            checked=((ReferenceMemory)result).checkPropertyReference(env,entity);
                            result.assign(checked);
                        } else result=source.finishCoercion(result.toValue(),checked);
                    } finally {php.runtime.PhpPropertyWriteContext.leave(writeContext);}
                }
            } finally {
                env.popCall();
            }
            return result;
        }

        if (quietRead) return Memory.NULL;
        if (accessFlag != 0)
            errorInvalidPropertyAccess(env, trace, entity, accessFlag);

        if (entity != null && entity.getDeclaredType() != null) entity.readInitialized(env, trace, null);
        env.error(trace, usesModernPropertyPolicy(env, trace) ? ErrorType.E_WARNING : ErrorType.E_NOTICE,
                Messages.ERR_UNDEFINED_PROPERTY, getName(), property);
        return Memory.NULL;
    }

    public void setProperty(IObject object, String name, Memory value) {
        PropertyEntity prop = findProperty(name);
        if (prop == null)
            throw new RuntimeException("Property '" + name + "' not found");

        object.getProperties().put(prop.specificName, value == null ? Memory.NULL : value);
    }

    private Memory getInstanceValue(Environment env, TraceInfo trace, IObject object, PropertyEntity property) throws Throwable {
        ArrayMemory props = object.getProperties();
        if (props instanceof php.runtime.memory.PhpObjectProperties
                && ((php.runtime.memory.PhpObjectProperties) props).isExplicitlyUnset(property.getSpecificName())) return null;
        return property.getValue(env, trace, object);
    }

    private ReferenceMemory findDynamicSlot(Environment env, TraceInfo trace, ArrayMemory props, String name,
                                             PropertyCallCache cache, int index) {
        if (props == null) return null;
        boolean cacheable = cache != null && !(env instanceof ConcurrentEnvironment)
                && usesModernPropertyPolicy(env, trace);
        if (cacheable) props.enableBucketLayout();
        ClassEntity scope = env.getLastClassOnStack();
        if (cacheable) {
            int ordinal = cache.getDynamicOffset(env, index, this, scope, name);
            ReferenceMemory cell = props.bucketAt(ordinal);
            if (cell != null && name.equals(props.bucketKey(ordinal))) return cell;
        }
        ReferenceMemory cell = props.getByScalar(name);
        if (cacheable && cell != null) cache.putDynamicOffset(env, index, this, scope, name, props.bucketOrdinal(cell));
        return cell;
    }

    private PropertyEntity findCachedInstanceProperty(Environment env, TraceInfo trace, String property, PropertyCallCache callCache, int cacheIndex) {
        ClassEntity context = env.getLastClassOnStack();
        boolean cacheable = callCache != null && !(env instanceof ConcurrentEnvironment);
        boolean modern = usesModernPropertyPolicy(env, trace);
        PropertyEntity entity = cacheable ? callCache.get(env, cacheIndex, this, context) : null;
        if (entity != null && (!entity.getName().equals(property) || modern && isHiddenInheritedPrivate(entity, context)))
            entity = null;
        if (entity == null) {
            entity = isInstanceOf(context) ? context.properties.get(property) : properties.get(property);
            if (modern && isHiddenInheritedPrivate(entity, context)) {
                // A parent's private slot is separate from the child's plain dynamic slot.
                // The declaring scope still resolves its private slot, including through a child.
                entity = properties.get(property);
                if (isHiddenInheritedPrivate(entity, context)) entity = null;
            }
            if (cacheable && entity != null) callCache.put(env, cacheIndex, entity, this, context);
        }
        return entity;
    }

    private boolean isHiddenInheritedPrivate(PropertyEntity entity, ClassEntity context) {
        return entity != null && entity.isPrivate() && entity.getClazz() != this && entity.getClazz() != context;
    }

    private static interface SetterCallback {
        Memory invoke(Memory o1, Memory o2);
        default Memory invokeTarget(ReferenceMemory target, Memory right) { return invoke(target, right); }
        default boolean assignsTarget() { return false; }
        default boolean incrementDecrement() { return false; }
        default Memory afterWarningFailure(ReferenceMemory target, Memory right) { return invokeTarget(target, right); }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassEntity)) return false;
        if (!super.equals(o)) return false;

        ClassEntity entity = (ClassEntity) o;

        if (id != entity.id) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (int) (id ^ (id >>> 32));
        return result;
    }


    public static class InvalidConstant {
        public enum Kind { INVALID_OVERRIDE, MUST_BE_PUBLIC, MUST_BE_PUBLIC_FOR_INTERFACE, MUST_BE_PROTECTED, FINAL, PRIVATE_FINAL, AMBIGUOUS }

        public final Kind kind;
        public final ConstantEntity constant;
        public final ConstantEntity prototype;
        public final ErrorType errorType;

        protected InvalidConstant(Kind kind, ConstantEntity constant, ConstantEntity prototype, ErrorType errorType) {
            this.kind = kind;
            this.constant = constant;
            this.errorType = errorType;
            this.prototype = prototype;
        }

        public static InvalidConstant compile(Kind kind, ConstantEntity constant, ConstantEntity prototype) {
            return new InvalidConstant(kind, constant, prototype, ErrorType.E_COMPILE_ERROR);
        }

        public static InvalidConstant error(Kind kind, ConstantEntity constant, ConstantEntity prototype) {
            return new InvalidConstant(kind, constant, prototype, ErrorType.E_ERROR);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InvalidConstant)) return false;

            InvalidConstant that = (InvalidConstant) o;

            return constant.equals(that.constant);
        }

        @Override
        public int hashCode() {
            return constant.hashCode();
        }
    }

    public static class InvalidMethod {
        public enum Kind {
            NON_EXISTS, INVALID_SIGNATURE, MUST_STATIC, MUST_NON_STATIC, MAGIC_MUST_BE_PUBLIC, MUST_BE_PUBLIC, MUST_BE_PROTECTED,
            FINAL, NON_ABSTRACT, NON_ABSTRACTABLE, INVALID_ACCESS_FOR_INTERFACE, FINAL_ABSTRACT, OVERRIDE_CONSTANTS
        }

        public final Kind kind;
        public final MethodEntity method;
        public final ErrorType errorType;

        protected InvalidMethod(Kind kind, MethodEntity method, ErrorType errorType) {
            this.kind = kind;
            this.method = method;
            this.errorType = errorType;
        }

        public static InvalidMethod warning(Kind kind, MethodEntity method) {
            return new InvalidMethod(kind, method, ErrorType.E_WARNING);
        }

        public static InvalidMethod error(Kind kind, MethodEntity method) {
            return new InvalidMethod(kind, method, ErrorType.E_ERROR);
        }

        public static InvalidMethod strict(Kind kind, MethodEntity method) {
            return new InvalidMethod(kind, method, ErrorType.E_STRICT);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InvalidMethod)) return false;

            InvalidMethod that = (InvalidMethod) o;

            return method.equals(that.method) && kind.equals(that.kind);
        }

        @Override
        public int hashCode() {
            int result = kind.hashCode();
            result = 31 * result + method.hashCode();
            return result;
        }
    }

    public static class InvalidProperty {
        public enum Kind {MUST_BE_PROTECTED, MUST_BE_PUBLIC, STATIC_AS_NON_STATIC, NON_STATIC_AS_STATIC}

        public final Kind kind;
        public final PropertyEntity property;
        public final ErrorType errorType;

        protected InvalidProperty(Kind kind, PropertyEntity property, ErrorType errorType) {
            this.kind = kind;
            this.property = property;
            this.errorType = errorType;
        }

        public static InvalidProperty warning(Kind kind, PropertyEntity property) {
            return new InvalidProperty(kind, property, ErrorType.E_WARNING);
        }

        public static InvalidProperty error(Kind kind, PropertyEntity property) {
            return new InvalidProperty(kind, property, ErrorType.E_ERROR);
        }

        public static InvalidProperty strict(Kind kind, PropertyEntity property) {
            return new InvalidProperty(kind, property, ErrorType.E_STRICT);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InvalidProperty)) return false;

            InvalidProperty that = (InvalidProperty) o;

            return property.equals(that.property) && kind.equals(that.kind);
        }

        @Override
        public int hashCode() {
            int result = kind.hashCode();
            result = 31 * result + property.hashCode();
            return result;
        }
    }

    public class PropertyResult {
        private final Set<InvalidProperty> properties;

        public PropertyResult() {
            this.properties = new HashSet<InvalidProperty>();
        }

        public void add(InvalidProperty prop) {
            this.properties.add(prop);
        }

        public void addError(InvalidProperty.Kind kind, PropertyEntity prop) {
            add(InvalidProperty.error(kind, prop));
        }

        public void check(Environment env) {
            for (InvalidProperty el : properties) {
                ErrorException e = null;
                switch (el.kind) {
                    case MUST_BE_PROTECTED:
                        e = new FatalException(
                                Messages.ERR_ACCESS_LEVEL_MUST_BE_PROTECTED_OR_WEAKER.fetch(
                                        el.property.clazz.getName(),
                                        el.property.getName(),
                                        el.property.getPrototype().getClazz().getName()
                                ),
                                el.property.getTrace()
                        );
                        break;
                    case MUST_BE_PUBLIC:
                        e = new FatalException(
                                Messages.ERR_ACCESS_LEVEL_MUST_BE_PUBLIC.fetch(
                                        el.property.clazz.getName(),
                                        el.property.getName(),
                                        el.property.getPrototype().getClazz().getName()
                                ),
                                el.property.getTrace()
                        );
                        break;
                    case STATIC_AS_NON_STATIC:
                        e = new FatalException(
                                Messages.ERR_CANNOT_REDECLARE_STATIC_AS_NON_STATIC.fetch(
                                        el.property.getPrototype().getClazz().getName(),
                                        el.property.getPrototype().getName(),
                                        el.property.clazz.getName(),
                                        el.property.getName()
                                ),
                                el.property.getTrace()
                        );
                        break;
                    case NON_STATIC_AS_STATIC:
                        e = new FatalException(
                                Messages.ERR_CANNOT_REDECLARE_NON_STATIC_AS_STATIC.fetch(
                                        el.property.getPrototype().getClazz().getName(),
                                        el.property.getPrototype().getName(),
                                        el.property.clazz.getName(),
                                        el.property.getName()
                                ),
                                el.property.getTrace()
                        );
                        break;
                }

                if (e != null) {
                    if (env == null)
                        throw e;
                    else
                        env.error(e.getTraceInfo(), el.errorType, e.getMessage());
                }
            }
        }
    }

    public class ImplementsResult {
        ClassEntity parent;
        SignatureResult signature;

        ImplementsResult(ClassEntity parent) {
            this.parent = parent;
            this.signature = new SignatureResult();
        }

        public void check(Environment env) {
            if (parent != null) {
                if (parent.isNotRuntime && !isInternal()) {
                    FatalException e = new FatalException(
                            Messages.ERR_CANNOT_USE_SYSTEM_CLASS.fetch(parent.getName(), getName()),
                            ClassEntity.this.trace
                    );
                    if (env != null)
                        env.error(e.getTraceInfo(), e.getType(), e.getMessage());
                    else
                        throw e;
                }
            }
            if (signature != null)
                signature.check(env);
        }
    }

    public class ExtendsResult {
        ClassEntity parent;
        SignatureResult methods;

        ExtendsResult(ClassEntity parent) {
            this.parent = parent;
            this.methods = new SignatureResult();
        }

        public void check(Environment env) {
            if (parent != null) {
                if (parent.isNotRuntime && !isInternal()) {
                    FatalException e = new FatalException(
                            Messages.ERR_CANNOT_USE_SYSTEM_CLASS.fetch(parent.getName(), getName()),
                            ClassEntity.this.trace
                    );
                    if (env != null)
                        env.error(e.getTraceInfo(), e.getType(), e.getMessage());
                    else
                        throw e;
                }

                if (parent.isFinal) {
                    FatalException e = new FatalException(
                            Messages.ERR_CLASS_MAY_NOT_INHERIT_FINAL_CLASS.fetch(ClassEntity.this.getName(), parent.getName()),
                            ClassEntity.this.trace
                    );
                    if (env != null)
                        env.error(e.getTraceInfo(), e.getType(), e.getMessage());
                    else
                        throw e;
                }
                if (ClassEntity.this.type == Type.CLASS && parent.type != Type.CLASS) {
                    FatalException e = new FatalException(
                            Messages.ERR_CANNOT_EXTENDS.fetch(ClassEntity.this.getName(), parent.getName()),
                            ClassEntity.this.trace
                    );
                    if (env != null)
                        env.error(e.getTraceInfo(), e.getType(), e.getMessage());
                    else
                        throw e;
                }
            }

            if (methods != null) {
                methods.check(env);
            }
        }
    }

    public class SignatureResult {
        private final Set<InvalidMethod> methods;
        private Set<InvalidConstant> overrideConstants_;

        SignatureResult() {
            methods = new HashSet<>();
        }

        public void add(InvalidMethod el) {
            methods.add(el);
        }

        public void addConstant(InvalidConstant el) {
            if (overrideConstants_ == null)
                overrideConstants_ = new HashSet<>();

            overrideConstants_.add(el);
        }

        public void check() {
            check(null);
        }

        private ErrorException getException(Environment env, InvalidMethod el, Messages.Item message) {
            return getException(env, el, message, false);
        }

        private ErrorException getException(Environment env, InvalidMethod el, Messages.Item message, boolean prototype) {
            ErrorException e;
            if (prototype) {
                e = new FatalException(
                        message.fetch(
                                el.method.getPrototype().getSignatureString(false),
                                el.method.getSignatureString(false)
                        ),
                        el.method.getTrace()
                );
            } else {
                e = new FatalException(
                        message.fetch(el.method.getSignatureString(false)),
                        el.method.getTrace()
                );
            }
            return e;
        }

        public void check(Environment env) {
            if (overrideConstants_ != null)
                for (InvalidConstant el : this.overrideConstants_) {
                    ErrorException e = null;

                    switch (el.kind) {
                        case FINAL:
                            e = new FatalException(el.constant.getClazz().getName() + "::" + el.constant.getName()
                                    + " cannot override final constant " + el.prototype.getClazz().getName() + "::" + el.prototype.getName(), getTrace());
                            break;
                        case PRIVATE_FINAL:
                            e = new FatalException("Private constant " + el.constant.getClazz().getName() + "::" + el.constant.getName()
                                    + " cannot be final as it is not visible to other classes", getTrace());
                            break;
                        case AMBIGUOUS:
                            e = new FatalException((isInterface() ? "Interface " : "Class ") + getName() + " inherits both "
                                    + el.constant.getClazz().getName() + "::" + el.constant.getName() + " and "
                                    + el.prototype.getClazz().getName() + "::" + el.prototype.getName() + ", which is ambiguous", getTrace());
                            break;
                        case INVALID_OVERRIDE:
                            e = new FatalException(Messages.ERR_CANNOT_INHERIT_OVERRIDE_CONSTANT.fetch(
                                            el.constant.getName(), el.prototype.getClazz().getName()
                            ), getTrace());
                            break;

                        case MUST_BE_PUBLIC:
                            e = new FatalException(Messages.ERR_ACCESS_LEVEL_CONSTANT_MUST_BE_PUBLIC.fetch(
                                    el.constant.getClazz().getName(),
                                    el.constant.getName(), el.prototype.getClazz().getName()
                            ).replace("(as in class ", el.prototype.getClazz().isInterface() ? "(as in interface " : "(as in class "), getTrace());
                            break;

                        case MUST_BE_PUBLIC_FOR_INTERFACE:
                            e = new FatalException(Messages.ERR_ACCESS_LEVEL_CONSTANT_MUST_BE_PUBLIC_FOR_INTERFACE.fetch(
                                    el.constant.getClazz().getName(),
                                    el.constant.getName()
                            ), getTrace());
                            break;

                        case MUST_BE_PROTECTED:
                            e = new FatalException(Messages.ERR_ACCESS_LEVEL_CONSTANT_MUST_BE_PROTECTED.fetch(
                                    el.constant.getClazz().getName(),
                                    el.constant.getName(), el.prototype.getClazz().getName()
                            ) + (hasPhp8Signatures() ? " or weaker" : ""), getTrace());
                            break;
                    }

                    if (e != null) {
                        if (env == null)
                            throw e;
                        else {
                            env.error(e.getTraceInfo(), el.errorType, e.getMessage());
                        }
                    }
                }

            Set<InvalidMethod> nonExists = null;
            for (InvalidMethod el : methods) {
                ErrorException e = null;
                switch (el.kind) {
                    case INVALID_SIGNATURE:
                        MethodEntity prototype = el.method.getPrototype();
                        if (!prototype.isAbstractable()) {
                            while (prototype.prototype != null && prototype.prototype.isAbstractable())
                                prototype = prototype.prototype;
                        }
                        e = new FatalException(
                                Messages.ERR_INVALID_METHOD_SIGNATURE.fetch(
                                        el.method.getSignatureString(true), prototype.getSignatureString(true)
                                ),
                                el.method.getTrace()
                        );
                        break;
                    case MUST_STATIC:
                        e = new FatalException(
                                Messages.ERR_CANNOT_MAKE_STATIC_TO_NON_STATIC.fetch(
                                        el.method.getPrototype().getSignatureString(false),
                                        ClassEntity.this.getName()
                                ),
                                el.method.getTrace()
                        );
                        break;
                    case MUST_NON_STATIC:
                        e = new FatalException(
                                Messages.ERR_CANNOT_MAKE_NON_STATIC_TO_STATIC.fetch(
                                        el.method.getPrototype().getSignatureString(false),
                                        ClassEntity.this.getName()
                                ),
                                el.method.getTrace()
                        );
                        break;
                    case MAGIC_MUST_BE_PUBLIC:
                        env.error(el.method.getTrace(), el.errorType,
                                "The magic method %s must have public visibility", el.method.getSignatureString(false)
                        );
                        break;
                    case MUST_BE_PROTECTED:
                        e = new FatalException(
                                Messages.ERR_ACCESS_LEVEL_METHOD_MUST_BE_PROTECTED_OR_WEAKER.fetch(
                                        el.method.clazz.getName(),
                                        el.method.getName(),
                                        el.method.getPrototype().getClazz().getName()
                                ),
                                el.method.getTrace()
                        );
                        break;
                    case MUST_BE_PUBLIC:
                        e = new FatalException(
                                Messages.ERR_ACCESS_LEVEL_METHOD_MUST_BE_PUBLIC.fetch(
                                        el.method.clazz.getName(),
                                        el.method.getName(),
                                        el.method.getPrototype().getClazz().getName()
                                ),
                                el.method.getTrace()
                        );
                        break;
                    case FINAL_ABSTRACT:
                        e = getException(env, el, Messages.ERR_CANNOT_USE_FINAL_ON_ABSTRACT);
                        break;
                    case FINAL:
                        e = getException(env, el, Messages.ERR_CANNOT_OVERRIDE_FINAL_METHOD, true);
                        break;
                    case NON_ABSTRACT:
                        e = getException(env, el, Messages.ERR_NON_ABSTRACT_METHOD_MUST_CONTAIN_BODY);
                        break;
                    case NON_ABSTRACTABLE:
                        e = getException(env, el, Messages.ERR_ABSTRACT_METHOD_CANNOT_CONTAIN_BODY);
                        break;
                    case INVALID_ACCESS_FOR_INTERFACE:
                        e = getException(env, el, Messages.ERR_ACCESS_TYPE_FOR_INTERFACE_METHOD);
                        break;
                    case NON_EXISTS:
                        if (nonExists == null) nonExists = new HashSet<>();
                        nonExists.add(el);
                        break;
                }

                if (e != null) {
                    if (env == null)
                        throw e;
                    else {
                        env.error(e.getTraceInfo(), el.errorType, e.getMessage());
                    }
                }
            }

            if (nonExists != null) {
                StringBuilder needs = new StringBuilder();
                Iterator<InvalidMethod> iterator = nonExists.iterator();
                int size = 0;
                ErrorType errorType = ErrorType.E_NOTICE;

                while (iterator.hasNext()) {
                    InvalidMethod el = iterator.next();
                    if (el.errorType.value < errorType.value)
                        errorType = el.errorType;

                    needs.append(el.method.getClazz().getName())
                            .append("::")
                            .append(el.method.getName());

                    if (iterator.hasNext())
                        needs.append(", ");
                    size++;
                }

                ErrorException e = new FatalException(
                        Messages.ERR_IMPLEMENT_METHOD.fetch(ClassEntity.this.getName(), size, needs),
                        ClassEntity.this.getTrace()
                );

                if (env == null)
                    throw e;
                else {
                    env.error(e.getTraceInfo(), errorType, e.getMessage());
                }
            }
        }
    }
}
