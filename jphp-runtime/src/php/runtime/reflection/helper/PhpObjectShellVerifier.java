package php.runtime.reflection.helper;

import php.runtime.Memory;
import php.runtime.PhpOwnershipObjects;
import php.runtime.PhpOwnershipValues;
import php.runtime.memory.*;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.loader.RuntimeClassLoader;
import php.runtime.reflection.ClassEntity;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.security.MessageDigest;

/** Actual loaded constructor/initializer proof only; PHP methods and resolved default values need their own contracts. */
public final class PhpObjectShellVerifier {
    private PhpObjectShellVerifier() {}

    /** Does not run class initialization or arbitrary native constructors. */
    public static Constructor<?> requireLoadedShell(ClassEntity entity) {
        if (entity == null) throw new IllegalArgumentException("Missing generated object shell");
        Set<ClassEntity> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for (ClassEntity current=entity;current != null;current=current.getParent()) {
            if (!visited.add(current)) throw new IllegalArgumentException("Cyclic generated object hierarchy");
            Class<?> actual=current.getNativeClass();
            ClassEntity parent=current.getParent();
            Class<?> expectedParent=parent == null ? BaseObject.class : parent.getNativeClass();
            if (actual == null || expectedParent == null || actual.getSuperclass() != expectedParent
                    || !(actual.getClassLoader() instanceof RuntimeClassLoader)
                    || !((RuntimeClassLoader)actual.getClassLoader()).matchesDefinedBytecode(actual,current.getData())
                    || !PhpClosureShellVerifier.verifyPropertiesShell(current.getData(),actual.getName(),expectedParent.getName()))
                throw new IllegalArgumentException("Object shell lacks its exact loaded constructor/initializer proof");
        }
        try {return entity.getNativeClass().getConstructor(Environment.class,ClassEntity.class);}
        catch (NoSuchMethodException absent) {throw new IllegalArgumentException("Missing generated object shell constructor",absent);}
    }

    /**
     * Register after actual PHP default initialization; the allocator still requires all certified defaults.
     * Only the proved literal/cache JVM initializers may run here, before minting allocation authority.
     */
    public static PhpOwnershipObjects.Provider register(PhpOwnershipObjects objects,Environment environment,ClassEntity entity) {
        objects.domain().currentContext();
        Constructor<?> constructor=requireLoadedShell(entity);
        validateResolved(environment,entity);
        var lineage=new ArrayList<ClassEntity>();
        for (ClassEntity current=entity;current != null;current=current.getParent()) lineage.add(current);
        try {
            for (int i=lineage.size()-1;i>=0;i--) {
                Class<?> actual=lineage.get(i).getNativeClass();
                Class.forName(actual.getName(),true,actual.getClassLoader());
            }
        } catch (ClassNotFoundException impossible) {
            throw new IllegalArgumentException("Proved object shell disappeared from its defining loader",impossible);
        }
        return objects.registerPropertiesProvider(entity,constructor,(selected,env) -> {
            if (selected != entity || env != environment)
                throw new IllegalArgumentException("Generated object shell protocol belongs to another class/environment");
            requireLoadedShell(selected);
            validateResolved(env,selected);
        });
    }

    /** Pure closed-default proof. No class initialization or PHP value lookup occurs here. */
    public static LiteralDefaultsPlan prepareLiteralDefaults(ClassEntity entity,Environment environment) {
        return new LiteralDefaultsPlan(Objects.requireNonNull(entity),Objects.requireNonNull(environment));
    }

    /** Literal NEW may use a destructor only with its already registered exact lifecycle body. */
    public static LiteralDefaultsPlan prepareLiteralDefaults(ClassEntity entity,Environment environment,
            PhpOwnershipObjects.CompleteDestructorProtocol protocol) {
        Objects.requireNonNull(entity);Objects.requireNonNull(environment);
        return entity.methodDestruct==null?new LiteralDefaultsPlan(entity,environment)
                :new LiteralDefaultsPlan(entity,environment,entity.methodDestruct,Objects.requireNonNull(protocol));
    }

    /** Separate pure restoration plan; the exact registered destructor body remains an explicit capability. */
    public static LifecycleDefaultsPlan prepareLifecycleDefaults(ClassEntity entity,Environment environment,
            php.runtime.reflection.MethodEntity destructor,PhpOwnershipObjects.CompleteDestructorProtocol protocol) {
        return new LifecycleDefaultsPlan(new LiteralDefaultsPlan(Objects.requireNonNull(entity),Objects.requireNonNull(environment),
                Objects.requireNonNull(destructor),Objects.requireNonNull(protocol)));
    }
    public static final class LifecycleDefaultsPlan {
        private final LiteralDefaultsPlan plan;
        private LifecycleDefaultsPlan(LiteralDefaultsPlan plan) {this.plan=plan;}
        public ClassEntity entity() {return plan.entity();}
        public Environment environment() {return plan.environment();}
        public String fingerprint() {return plan.fingerprint();}
        public void validate() {plan.validate();}
        public PhpOwnershipObjects.Provider initializeAndRegister(PhpOwnershipObjects objects) {return plan.initializeAndRegister(objects);}
        public Map<PropertyEntity,PhpOwnershipValues.Binding> certifiedDefaults(PhpOwnershipObjects objects) {return plan.certifiedDefaults(objects);}
    }

    /** Compiler metadata digest only; actual admission additionally requires the loaded-shell plan. */
    public static String fingerprintLiteralDefaults(ClassEntity entity) {
        Objects.requireNonNull(entity);return digest(capture(entity,false,entity.methodDestruct!=null));
    }

    public static final class LiteralDefaultsPlan {
        private final ClassEntity entity;
        private final Environment environment;
        private final List<ClassState> expected;
        private final String fingerprint;
        private final php.runtime.reflection.MethodEntity destructor;
        private final java.lang.reflect.Method destructorMember;
        private final PhpOwnershipObjects.CompleteDestructorProtocol destructorProtocol;
        private final IdentityHashMap<PhpOwnershipObjects,PhpOwnershipObjects.Provider> providers=new IdentityHashMap<>();
        private LiteralDefaultsPlan(ClassEntity entity,Environment environment) {this(entity,environment,null,null);}
        private LiteralDefaultsPlan(ClassEntity entity,Environment environment,php.runtime.reflection.MethodEntity destructor,
                PhpOwnershipObjects.CompleteDestructorProtocol destructorProtocol) {
            this.entity=entity;this.environment=environment;this.destructor=destructor;this.destructorProtocol=destructorProtocol;
            this.destructorMember=destructor==null?null:destructor.getNativeMethod();
            validateDestructor();expected=capture(entity,true,destructor!=null);
            for (ClassState state:expected) for (PropertyState property:state.properties) checkedDefault(property.property);
            fingerprint=digest(expected);
        }
        public ClassEntity entity() {return entity;}
        public Environment environment() {return environment;}
        public String fingerprint() {validate();return fingerprint;}
        private void validateDestructor() {
            if (destructor==null) return;
            if (destructorProtocol==null || entity.methodDestruct!=destructor || destructor.isStatic() || destructor.isAbstract()
                    || destructor.getParameters()!=null && destructor.getParameters().length!=0
                    || destructorMember==null || entity.getNativeClass()==null || !destructorMember.equals(destructor.getNativeMethod())
                    || !destructorMember.getDeclaringClass().isAssignableFrom(entity.getNativeClass())
                    || !java.util.Arrays.equals(destructorMember.getParameterTypes(),new Class<?>[]{Environment.class,Memory[].class})
                    || destructorMember.getReturnType()!=Memory.class)
                throw new IllegalArgumentException("Lifecycle defaults require their exact registered no-argument destructor");
            destructorProtocol.validateMember(entity,destructor,environment);
        }
        public void validate() {
            validateDestructor();
            List<ClassState> actual=capture(entity,true,destructor!=null);
            if (!expected.equals(actual))
                throw new IllegalStateException("Literal object default descriptor changed after its proof");
            for (int i=0;i<expected.size();i++) {
                ClassState old=expected.get(i),now=actual.get(i);
                if (old.entity != now.entity || old.parent != now.parent || old.module != now.module || old.nativeClass != now.nativeClass || old.constructor != now.constructor)
                    throw new IllegalStateException("Literal class identity changed");
                for (int j=0;j<old.properties.size();j++) {
                    PropertyState before=old.properties.get(j),after=now.properties.get(j);
                    if (before.property != after.property || before.owner != after.owner || before.type != after.type || before.raw != after.raw)
                        throw new IllegalStateException("Literal property identity changed");
                    checkedDefault(after.property);
                }
            }
        }
        /** Legal initialization of only the already proved scalar validation chain. */
        public PhpOwnershipObjects.Provider initializeAndRegister(PhpOwnershipObjects objects) {
            if (Objects.requireNonNull(objects).domain().currentContext() != environment)
                throw new IllegalArgumentException("Literal object plan requires its actual Environment execution");
            validate();
            PhpOwnershipObjects.Provider existing=providers.get(objects);
            if (existing != null) {
                if (objects.provider(entity) != existing) throw new IllegalStateException("Literal object provider changed");
                validateResolved(environment,entity);return existing;
            }
            if (destructor!=null) {
                boolean registered;
                try {objects.provider(entity);registered=true;}catch(IllegalArgumentException absent){registered=false;}
                if (registered) throw new IllegalStateException("Lifecycle plan cannot adopt another allocation provider");
            }
            entity.initializeRuntimeState(environment);validate();validateResolved(environment,entity);
            // Validate scalar types before registering; no arbitrary provider may be adopted.
            for (ClassState state:expected) for (PropertyState property:state.properties)
                checkedDefault(property.property);
            Constructor<?> constructor=requireLoadedShell(entity);
            try {
                for (int i=expected.size()-1;i>=0;i--) {
                    Class<?> actual=expected.get(i).nativeClass;
                    Class.forName(actual.getName(),true,actual.getClassLoader());
                }
            } catch (ClassNotFoundException impossible) {
                throw new IllegalArgumentException("Proved literal shell disappeared",impossible);
            }
            validate();
            PhpOwnershipObjects.CompletePropertiesProtocol propertiesProtocol=(selected,env) -> {
                if (selected != entity || env != environment) throw new IllegalArgumentException("Literal provider belongs to another class/environment");
                validate();validateResolved(environment,entity);
            };
            PhpOwnershipObjects.Provider provider=destructor==null
                    ?objects.registerPropertiesProvider(entity,constructor,propertiesProtocol)
                    :objects.registerPropertiesProvider(entity,constructor,propertiesProtocol,destructor,destructorProtocol);
            providers.put(objects,provider);return provider;
        }
        public Map<PropertyEntity,PhpOwnershipValues.Binding> certifiedDefaults(PhpOwnershipObjects objects) {
            if (Objects.requireNonNull(objects).domain().currentContext() != environment)
                throw new IllegalArgumentException("Literal object plan requires its actual Environment execution");
            validate();validateResolved(environment,entity);
            PhpOwnershipObjects.Provider provider=providers.get(objects);
            if (provider == null || objects.provider(entity) != provider)
                throw new IllegalStateException("Literal defaults require this plan's actual provider");
            var result=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for (PropertyEntity property:provider.properties())
                result.put(property,PhpOwnershipValues.bindValue(objects.domain(),checkedDefault(property),null));
            return result;
        }
        private Memory checkedDefault(PropertyEntity property) {
            Memory raw=property.getDefaultValue();scalar(raw,property);
            if (raw == Memory.UNDEFINED || property.getDeclaredType() == null) return raw;
            Memory checked=property.getDeclaredType().strictValue(environment,raw,property.getClazz());
            if (checked == null) throw new IllegalArgumentException("Literal object default violates its scalar type");
            scalar(checked,property);return checked;
        }
    }

    private record ClassState(ClassEntity entity,ClassEntity parent,ModuleEntity module,Class<?> nativeClass,
                              Constructor<?> constructor,String name,String internalName,boolean deferred,
                              boolean abstractClass,boolean finalClass,String bytes,List<PropertyState> properties) {}
    private record PropertyState(PropertyEntity property,ClassEntity owner,ClassEntity trait,String name,
                                 String internalName,String specificName,php.runtime.common.Modifier modifier,
                                 boolean finalProperty,boolean defaultFlag,boolean hasDefault,
                                 PhpTypeDeclaration type,String signature,Memory raw,String value) {}
    private static List<ClassState> capture(ClassEntity entity) {return capture(entity,true);}
    private static List<ClassState> capture(ClassEntity entity,boolean loaded) {return capture(entity,loaded,false);}
    private static List<ClassState> capture(ClassEntity entity,boolean loaded,boolean lifecycle) {
        Set<ClassEntity> lineage=Collections.newSetFromMap(new IdentityHashMap<>());
        for (ClassEntity current=entity;current != null;current=current.getParent()) {
            if (current.getClass() != ClassEntity.class || !lineage.add(current))
                throw new IllegalArgumentException("Literal object plan requires exact acyclic ClassEntity metadata");
            if (current.getData() == null)
                throw new IllegalArgumentException("Literal object plan requires compiler bytecode for its entire class hierarchy");
        }
        Constructor<?> constructor=loaded ? requireLoadedShell(entity) : null;
        var result=new ArrayList<ClassState>();
        for (ClassEntity current=entity;current != null;current=current.getParent()) {
            if (current.getClass() != ClassEntity.class || current.getType() != ClassEntity.Type.CLASS
                    || current.isAbstract() || current.methodConstruct != null || !lifecycle && current.methodDestruct != null
                    || current.findMethod("__construct") != null || !lifecycle && current.findMethod("__destruct") != null
                    || !current.getTraits().isEmpty() || !current.getTraitConstantChecks().isEmpty()
                    || !current.getConstants().isEmpty() || !current.getStaticProperties().isEmpty()
                    || current.getNativeInitEnvironment() != null)
                throw new IllegalArgumentException("Object defaults lack a closed literal initialization chain");
            var properties=new ArrayList<PropertyState>();
            for (PropertyEntity property:current.getProperties()) {
                if (property.getClass() != PropertyEntity.class || !lineage.contains(property.getClazz())
                        || property.getTrait() != null || property.isStatic()
                        || property.getGetter() != null || property.getSetter() != null)
                    throw new IllegalArgumentException("Literal object defaults cannot run property hooks");
                PhpTypeDeclaration type=property.getDeclaredType();
                if (type != null) for (var alternative:type.getAlternatives()) {
                    if (alternative.size() != 1 || !Set.of("int","float","string","bool","mixed","null","true","false").contains(alternative.getFirst()))
                        throw new IllegalArgumentException("Literal default type requires an unadmitted class lookup");
                }
                Memory raw=property.getDefaultValue();String value=scalar(raw,property);
                properties.add(new PropertyState(property,property.getClazz(),property.getTrait(),property.getName(),
                        property.getInternalName(),property.getSpecificName(),property.getModifier(),property.isFinal(),
                        property.isDefault(),property.hasDefaultValue(),type,type == null ? "" : type.getSignature(),raw,value));
            }
            Constructor<?> actual=null;
            if (loaded) try {
                Constructor<?> proved=current.getNativeClass().getConstructor(Environment.class,ClassEntity.class);
                actual=current.getOwnershipNativeConstructor();
                if (actual == null || !actual.equals(proved)) throw new IllegalArgumentException("Literal class constructor differs from its loaded-shell proof");
            } catch (NoSuchMethodException absent) {throw new IllegalArgumentException("Missing literal object constructor",absent);}
            result.add(new ClassState(current,current.getParent(),current.getModule(),current.getNativeClass(),
                    actual,current.getName(),current.getInternalName(),current.hasDeferredInitialization(),
                    current.isAbstract(),current.isFinal(),hex(current.getData()),List.copyOf(properties)));
        }
        if (loaded && constructor.getDeclaringClass() != entity.getNativeClass()) throw new IllegalArgumentException("Wrong literal constructor");
        return List.copyOf(result);
    }
    private static String scalar(Memory value,PropertyEntity property) {
        if (value == null) throw new IllegalArgumentException("Literal defaults cannot use deferred Java-null values");
        if (value == Memory.UNDEFINED) {
            if (property.getDeclaredType() == null || property.isDefault() || property.hasDefaultValue())
                throw new IllegalArgumentException("UNDEFINED requires a genuinely uninitialized declared property");
            return "undefined";
        }
        Class<?> type=value.getClass();
        if (type == LongMemory.class) return "int:"+value.toLong();
        if (type == DoubleMemory.class) return "float:"+Long.toUnsignedString(Double.doubleToRawLongBits(value.toDouble()));
        if (type == StringMemory.class) return "string:"+value.toString();
        if (type == TrueMemory.class) return "true";
        if (type == FalseMemory.class) return "false";
        if (type == NullMemory.class) return "null";
        throw new IllegalArgumentException("Object default lacks an exact primitive literal origin");
    }
    private static String digest(List<ClassState> states) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for (ClassState state:states) {
                fingerprintPart(digest,state.name);fingerprintPart(digest,state.internalName);fingerprintPart(digest,state.bytes);
                fingerprintPart(digest,"(Environment,ClassEntity) literal shell");fingerprintPart(digest,Boolean.toString(state.deferred));
                fingerprintPart(digest,Boolean.toString(state.finalClass));fingerprintPart(digest,Integer.toString(state.properties.size()));
                for (PropertyState property:state.properties) {
                    fingerprintPart(digest,property.owner.getName());fingerprintPart(digest,property.name);
                    fingerprintPart(digest,property.internalName);fingerprintPart(digest,property.specificName);
                    fingerprintPart(digest,property.modifier.name());fingerprintPart(digest,Boolean.toString(property.finalProperty));
                    fingerprintPart(digest,Boolean.toString(property.defaultFlag));fingerprintPart(digest,Boolean.toString(property.hasDefault));
                    fingerprintPart(digest,property.signature);fingerprintPart(digest,property.value);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static void fingerprintPart(MessageDigest digest,String value) {
        int length=Objects.requireNonNull(value).length();
        digest.update((byte)(length>>>24));digest.update((byte)(length>>>16));
        digest.update((byte)(length>>>8));digest.update((byte)length);
        for(int index=0;index<length;index++) {
            char unit=value.charAt(index);digest.update((byte)(unit>>>8));digest.update((byte)unit);
        }
    }
    private static String hex(byte[] bytes) {
        try {return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch (java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }

    private static void validateResolved(Environment environment,ClassEntity entity) {
        for (ClassEntity current=entity;current != null;current=current.getParent())
            if (!current.isRuntimeStateInitialized(environment))
                throw new IllegalStateException("Object defaults require completed admitted runtime initialization");
    }
}
