package php.runtime.reflection;

import java.util.*;

/** Bound physical composition recipe, not a class-template bytecode replacement or name resolver. */
public final class PhpTraitCompositionPlan {
    public record MethodCopy(MethodEntity donor, MethodEntity target, int targetJvmFlags) {
        public MethodCopy { Objects.requireNonNull(donor); Objects.requireNonNull(target); }
    }
    public record Bridge(ClassEntity nativeInterface, MethodEntity contract, MethodEntity target,
                         String javaName, String descriptor) {
        public Bridge {
            Objects.requireNonNull(nativeInterface); Objects.requireNonNull(contract);
            Objects.requireNonNull(target); Objects.requireNonNull(javaName); Objects.requireNonNull(descriptor);
        }
    }
    private record Source(ClassEntity entity, Class<?> physical, String compiled, String name, Object module, int ordinal, byte[] bytes) {
        Source(ClassEntity entity) {
            this(entity, entity.getNativeClass(), entity.getCompiledInternalName(), entity.getName(), entity.getModule(), entity.getDeclarationId(),
                    entity.getData() == null ? null : entity.getData().clone());
        }
        void validate() {
            if (entity.isDeclarationTemplate() || entity.isUnresolvedDeclaration()
                    || entity.getNativeClass() != physical || !Objects.equals(entity.getCompiledInternalName(), compiled)
                    || !Objects.equals(entity.getName(), name) || entity.getModule() != module || entity.getDeclarationId() != ordinal
                    || !Arrays.equals(entity.getData(), bytes))
                throw new LinkageError("Composition donor changed after declaration resolution");
        }
    }
    private record ParameterShape(ParameterEntity value, Object type, Object initial, Object initializer,
                                  String name, String constant, boolean nullable, boolean reference,
                                  boolean variadic, boolean promoted, boolean mutable, boolean used, ClassEntity initializerOwner) {
        ParameterShape(ParameterEntity value, ClassEntity initializerOwner) {
            this(value, value.getTypeChecker(), value.getDefaultValue(), value.getDefaultInitializer(),
                    value.getName(), value.getDefaultValueConstName(), value.isNullable(), value.isReference(),
                    value.isVariadic(), value.isPromoted(), value.isMutable(), value.isUsed(), initializerOwner);
        }
        private boolean sameInitializer() {
            Object now = value.getDefaultInitializer();
            if (Objects.equals(now, initializer)) return true;
            if (initializerOwner == null || initializerOwner.getNativeClass() == null
                    || !(initializer instanceof ParameterEntity.DefaultInitializer before)
                    || !(now instanceof ParameterEntity.DefaultInitializer after)) return false;
            return before.javaClass().replace('.', '/').equals(initializerOwner.getCompiledInternalName().replace('.', '/'))
                    && after.javaClass().equals(initializerOwner.getNativeClass().getName())
                    && after.moduleName().equals(before.moduleName()) && after.method().equals(before.method())
                    && Objects.equals(after.declarationScope(), before.declarationScope());
        }
        void validate() {
            if (value.getTypeChecker() != type || value.getDefaultValue() != initial
                    || !sameInitializer()
                    || !Objects.equals(value.getName(), name) || !Objects.equals(value.getDefaultValueConstName(), constant)
                    || value.isNullable() != nullable || value.isReference() != reference || value.isVariadic() != variadic
                    || value.isPromoted() != promoted || value.isMutable() != mutable || value.isUsed() != used)
                throw new LinkageError("Composition parameter contract changed");
        }
    }
    private static final class MethodShape {
        final MethodEntity method;
        final ClassEntity declaring, trait;
        final String internal, name;
        final Object modifier, resultType, generator, result;
        final boolean isStatic, isFinal, abstractMethod, returnReference, nullable;
        final java.lang.reflect.Method physical;
        final ParameterEntity[] parameters;
        final List<ParameterShape> shapes;
        final boolean installTarget;
        MethodShape(MethodEntity method, boolean installTarget) {
            this.method = method; this.installTarget = installTarget;
            declaring = method.getClazz(); trait = method.getTrait(); internal = method.getInternalName(); name = method.getName();
            modifier = method.getModifier(); resultType = method.getReturnTypeChecker(); generator = method.getGeneratorEntity(); result = method.getResult();
            isStatic = method.isStatic(); isFinal = method.isFinal(); abstractMethod = method.isAbstract();
            returnReference = method.isReturnReference(); nullable = method.isReturnTypeNullable(); physical = method.getNativeMethod();
            parameters = method.getParameters();
            List<ParameterShape> captured = new ArrayList<>();
            if (parameters != null) for (ParameterEntity parameter : parameters) captured.add(new ParameterShape(parameter, installTarget ? declaring : null));
            shapes = List.copyOf(captured);
        }
        void validate() {
            if (method.getClazz() != declaring || method.getTrait() != trait
                    || !Objects.equals(method.getInternalName(), internal) || !Objects.equals(method.getName(), name)
                    || method.getModifier() != modifier || method.getReturnTypeChecker() != resultType
                    || method.getGeneratorEntity() != generator || method.getResult() != result
                    || method.isStatic() != isStatic || method.isFinal() != isFinal || method.isAbstract() != abstractMethod
                    || method.isReturnReference() != returnReference || method.isReturnTypeNullable() != nullable
                    || method.getParameters() != parameters)
                throw new LinkageError("Composition selected method contract changed");
            if (parameters != null) for (int i = 0; i < parameters.length; i++) {
                if (parameters[i] != shapes.get(i).value()) throw new LinkageError("Composition parameter identity changed");
                shapes.get(i).validate();
            }
            java.lang.reflect.Method now = method.getNativeMethod();
            if (now != physical && !(installTarget && physical == null && now != null
                    && declaring.getNativeClass() != null && now.getDeclaringClass() == declaring.getNativeClass()
                    && now.getName().equals(internal)))
                throw new LinkageError("Composition physical method changed");
        }
    }
    private final ClassEntity owner;
    private final byte[] original;
    private final List<ClassEntity> interfaces, traits;
    private final List<MethodCopy> methods;
    private final List<Bridge> bridges;
    private final List<Source> sources;
    private final List<MethodShape> selections;

    PhpTraitCompositionPlan(ClassEntity owner, List<ClassEntity> interfaces, List<ClassEntity> traits,
                            List<MethodCopy> methods, List<Bridge> bridges) {
        this.owner = Objects.requireNonNull(owner);
        original = owner.getData() == null ? null : owner.getData().clone();
        this.interfaces = List.copyOf(interfaces); this.traits = List.copyOf(traits);
        this.methods = List.copyOf(methods); this.bridges = List.copyOf(bridges);
        Set<ClassEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Source> captured = new ArrayList<>();
        for (ClassEntity entity : interfaces) if (seen.add(entity)) captured.add(new Source(entity));
        for (ClassEntity entity : traits) if (seen.add(entity)) captured.add(new Source(entity));
        for (MethodCopy method : methods)
            if (seen.add(method.donor().getClazz())) captured.add(new Source(method.donor().getClazz()));
        sources = List.copyOf(captured);
        List<MethodShape> selected = new ArrayList<>();
        for (MethodCopy copy : methods) {
            selected.add(new MethodShape(copy.donor(), false)); selected.add(new MethodShape(copy.target(), true));
        }
        for (Bridge bridge : bridges) {
            selected.add(new MethodShape(bridge.contract(), false));
            selected.add(new MethodShape(bridge.target(), bridge.target().getClazz() == owner));
        }
        selections = List.copyOf(selected);
        validate();
    }
    public ClassEntity owner() { return owner; }
    public List<ClassEntity> interfaces() { return interfaces; }
    public List<ClassEntity> traits() { return traits; }
    public List<MethodCopy> methods() { return methods; }
    public List<Bridge> bridges() { return bridges; }
    private static boolean selected(ClassEntity owner,MethodEntity method) {
        return owner.findMethod(method.getLowerName())==method
                || php.runtime.reflection.support.PhpPropertyHooks.ownedMethod(owner,method)!=null;
    }
    public void validate() {
        if (owner.isDeclarationTemplate() || owner.isUnresolvedDeclaration()
                || !Arrays.equals(original, owner.getData()))
            throw new LinkageError("Composition owner is not its bound original template");
        for (Source source : sources) source.validate();
        for (MethodShape selection : selections) selection.validate();
        Set<String> targets = new HashSet<>();
        for (MethodCopy copy : methods) {
            if (copy.target().getClazz() != owner
                    || !selected(owner,copy.target())
                    || !selected(copy.donor().getClazz(),copy.donor())
                    || !targets.add(copy.target().getInternalName()))
                throw new LinkageError("Composition method no longer has its exact donor and target");
        }
        for (Bridge bridge : bridges) {
            if (!bridge.nativeInterface().isInterface() || bridge.nativeInterface().getNativeClass() == null
                    || !bridge.nativeInterface().getNativeClass().isInterface()
                    || owner.findMethod(bridge.target().getLowerName()) != bridge.target())
                throw new LinkageError("Composition bridge no longer has its actual interface and target");
        }
    }
}
