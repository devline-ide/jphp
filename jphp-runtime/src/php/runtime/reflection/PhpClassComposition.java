package php.runtime.reflection;

import java.util.*;
import php.runtime.Memory;
import php.runtime.common.Modifier;
import php.runtime.common.PhpClassNames;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.support.PhpMethodCompatibility;

/** Resolves declaration relations through Environment once, then builds metadata and a physical loader plan. */
final class PhpClassComposition {
    private PhpClassComposition() {}
    private record Choice(ClassEntity trait, MethodEntity donor, String name, Modifier visibility, boolean makeFinal) {}
    private static final String BRIDGE = "(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";

    static PhpTraitCompositionPlan compose(ClassEntity owner, Environment env) {
        PhpClassRelations recipe = owner.getDeclarationRelations();
        if (recipe.interfaces().isEmpty() && recipe.traitGroups().isEmpty()) {
            owner.finishDeferredDeclaration(env);
            return null;
        }
        List<ClassEntity> interfaces = new ArrayList<>(), traits = new ArrayList<>();
        List<List<ClassEntity>> groups = new ArrayList<>();
        Set<ClassEntity> directInterfaces = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ClassEntity> interfaceSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PhpClassRelations.InterfaceUse use : recipe.interfaces()) {
            if ((owner.isInterface()) != (use.kind() == PhpClassRelations.InterfaceKind.EXTENDS))
                fail(env, use.name().trace(), "Invalid interface relation in %s", owner.getName());
            ClassEntity actual = resolve(owner, env, use.name(), ClassEntity.Type.INTERFACE);
            if(!owner.isInternal() && actual.getNativeClass()!=null && actual.getNativeClass().isAnnotationPresent(php.runtime.annotation.Reflection.InternalInterface.class))
                fail(env,use.name().trace(),"%s can't be implemented by user classes",actual.getName());
            if (!directInterfaces.add(actual)) fail(env, use.name().trace(), "Interface %s cannot be implemented twice", actual.getName());
            collectInterfaces(actual, interfaces, interfaceSeen);
        }
        for (PhpClassRelations.TraitGroup group : recipe.traitGroups()) {
            if (owner.isInterface()) fail(env, group.trace(), "Cannot use traits inside of interfaces");
            List<ClassEntity> actual = new ArrayList<>();
            for (PhpClassRelations.Name name : group.traits()) {
                ClassEntity trait = resolve(owner, env, name, ClassEntity.Type.TRAIT);
                if (!actual.contains(trait)) actual.add(trait);
                if (!traits.contains(trait)) traits.add(trait);
            }
            groups.add(actual);
        }
        Map<String, Choice> selected = new LinkedHashMap<>();
        List<Choice> requirements = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++)
            select(owner, env, recipe.traitGroups().get(i), groups.get(i), selected, requirements);
        List<PhpTraitCompositionPlan.MethodCopy> copies = new ArrayList<>();
        for (ClassEntity trait : traits) {
            owner.addTrait(trait);
            properties(owner, trait, trait.getProperties(), env,copies);
            properties(owner, trait, trait.getStaticProperties(), env,copies);
            constants(owner, trait, env);
        }
        for (Choice choice : selected.values()) {
            MethodEntity existing = owner.findMethod(key(choice.name()));
            if (existing != null && existing.getClazz() == owner && existing.getTrait() == null) continue;
            if (choice.donor().isAbstract() && owner.getParent() != null) {
                MethodEntity inherited = owner.getParent().findMethod(key(choice.name()));
                if (inherited != null && !inherited.isAbstract() && !inherited.isPrivate()) continue;
            }
            MethodEntity target = copyMethod(owner, choice);
            target.setInternalName(target.getName() + "$" + owner.nextMethodIndex());
            owner.addMethod(target, null).check(env);
            copies.add(new PhpTraitCompositionPlan.MethodCopy(choice.donor(), target, donorFlags(choice.donor())
                    | (target.isFinal() ? java.lang.reflect.Modifier.FINAL : 0)));
        }
        owner.updateParentBody().check(env);
        for (Choice requirement : requirements) {
            MethodEntity required = copyMethod(owner, requirement);
            MethodEntity implementation = owner.findMethod(key(requirement.name()));
            if (implementation == null || !owner.isAbstract() && !owner.isTrait() && implementation.isAbstract()
                    || implementation != null && (implementation.isStatic() != required.isStatic()
                        || required.isPublic() && !implementation.isPublic()
                        || required.isProtected() && implementation.isPrivate()
                        || !PhpMethodCompatibility.compatible(implementation, required, owner.getModule(), env)))
                fail(env, required.getTrace(), "Declaration of %s must be compatible with %s",
                        implementation == null ? owner.getName() + "::" + required.getName() : implementation.getSignatureString(true),
                        required.getSignatureString(true));
        }
        for (ClassEntity contract : interfaces) owner.addInterface(contract).check(env);
        owner.finishDeferredDeclaration(env);
        List<PhpTraitCompositionPlan.Bridge> bridges = new ArrayList<>();
        Set<String> bridgeNames = new HashSet<>();
        for (ClassEntity contract : interfaces) {
            if (!contract.isInternal() || contract.getNativeClass() == null) continue;
            for (MethodEntity method : contract.getMethods().values()) {
                MethodEntity implementation = owner.findMethod(method.getLowerName());
                if (implementation != null && !implementation.isAbstract() && bridgeNames.add(method.getName()))
                    bridges.add(new PhpTraitCompositionPlan.Bridge(contract, method, implementation, method.getName(), BRIDGE));
            }
        }
        List<ClassEntity> physicalTraits = new ArrayList<>();
        Set<ClassEntity> traitSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ClassEntity trait : traits) collectTraits(trait, physicalTraits, traitSeen);
        for (PhpTraitCompositionPlan.MethodCopy copy : copies) collectTraits(copy.donor().getClazz(), physicalTraits, traitSeen);
        return new PhpTraitCompositionPlan(owner, interfaces, physicalTraits, copies, bridges);
    }

    private static ClassEntity resolve(ClassEntity owner, Environment env, PhpClassRelations.Name name, ClassEntity.Type expected) {
        ClassEntity actual = env.fetchClass(name.name(), true, owner.getModule().getPhpLanguageProfile());
        if (actual == null) {
            String kind = expected == ClassEntity.Type.TRAIT ? "Trait" : "Interface";
            env.exception(name.trace(), php.runtime.lang.exception.BaseError.class, "%s \"%s\" not found", kind, name.name());
            throw new IllegalStateException("Missing relation exception returned");
        }
        if (actual.getType() != expected) fail(env, name.trace(), "%s cannot use %s as %s", owner.getName(), name.name(), expected);
        if (actual.isDeclarationTemplate() || actual.isUnresolvedDeclaration())
            throw new LinkageError("An unexecuted declaration cannot satisfy a class relation");
        return actual;
    }
    private static void collectInterfaces(ClassEntity actual, List<ClassEntity> list, Set<ClassEntity> seen) {
        if (!seen.add(actual)) return;
        list.add(actual);
        for (ClassEntity ancestor : actual.getInterfaces().values()) collectInterfaces(ancestor, list, seen);
    }
    private static void collectTraits(ClassEntity trait, List<ClassEntity> list, Set<ClassEntity> seen) {
        if (!seen.add(trait)) return;
        list.add(trait);
        for (ClassEntity used : trait.getTraits().values()) collectTraits(used, list, seen);
    }
    private static String key(String name) { return PhpClassNames.canonicalKey(name); }
    private static ClassEntity named(List<ClassEntity> group, String name, Environment env, TraceInfo trace) {
        for (ClassEntity trait : group) if (key(trait.getName()).equals(key(name))) return trait;
        fail(env, trace, "Required Trait %s wasn't added", name); throw new IllegalStateException();
    }
    private static MethodEntity method(ClassEntity trait, String name, Environment env, TraceInfo trace) {
        MethodEntity result = trait.findMethod(key(name));
        if (result == null) fail(env, trace, "A precedence rule was defined for %s::%s but this method does not exist", trait.getName(), name);
        return result;
    }
    private static void select(ClassEntity owner, Environment env, PhpClassRelations.TraitGroup recipe, List<ClassEntity> group,
                               Map<String, Choice> all, List<Choice> requirements) {
        Map<String, List<Choice>> candidates = new LinkedHashMap<>();
        for (ClassEntity trait : group) for (MethodEntity method : trait.getMethods().values()) {
            Choice choice = new Choice(trait, method, method.getName(), null, false);
            List<Choice> values = candidates.computeIfAbsent(method.getLowerName(), ignored -> new ArrayList<>());
            if (values.stream().noneMatch(existing -> existing.donor() == method)) values.add(choice);
            if (method.isAbstract()) requirements.add(choice);
        }
        Map<String, Set<ClassEntity>> excluded = new HashMap<>();
        for (PhpClassRelations.Adaptation adaptation : recipe.adaptations()) if (adaptation instanceof PhpClassRelations.Precedence rule) {
            ClassEntity preferred = named(group, rule.preferredTrait(), env, rule.trace());
            method(preferred, rule.method(), env, rule.trace());
            Set<ClassEntity> removed = excluded.computeIfAbsent(key(rule.method()), ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
            for (PhpClassRelations.Name name : rule.excluded()) {
                ClassEntity other = named(group, name.name(), env, name.trace());
                method(other, rule.method(), env, name.trace());
                if (other == preferred || !removed.add(other)) fail(env, rule.trace(), "Inconsistent trait precedence for %s", rule.method());
            }
            if (removed.contains(preferred)) fail(env, rule.trace(), "Inconsistent trait precedence for %s", rule.method());
        }
        Map<String, Choice> winners = new LinkedHashMap<>();
        for (var entry : candidates.entrySet()) {
            Set<ClassEntity> removed = excluded.getOrDefault(entry.getKey(), Set.of());
            List<Choice> remaining = entry.getValue().stream().filter(choice -> !removed.contains(choice.trait())).toList();
            List<Choice> concrete = remaining.stream().filter(choice -> !choice.donor().isAbstract()).toList();
            MethodEntity own = owner.findMethod(entry.getKey());
            if (concrete.size() > 1 && !(own != null && own.getClazz() == owner && own.getTrait() == null))
                fail(env, recipe.trace(), "Trait method %s has not been applied as %s::%s, because of collision with other trait methods",
                        concrete.get(1).donor().getName(), owner.getName(), concrete.get(1).name());
            if (!concrete.isEmpty()) winners.put(entry.getKey(), concrete.get(0));
            else if (!remaining.isEmpty()) winners.put(entry.getKey(), remaining.get(0));
        }
        for (PhpClassRelations.Adaptation adaptation : recipe.adaptations()) if (adaptation instanceof PhpClassRelations.Alias alias) {
            Choice source;
            if (alias.trait() != null) {
                ClassEntity actual = named(group, alias.trait(), env, alias.trace());
                source = new Choice(actual, method(actual, alias.method(), env, alias.trace()), alias.method(), null, false);
            } else {
                List<Choice> eligible = candidates.getOrDefault(key(alias.method()), List.of());
                if (eligible.size() > 1) fail(env, alias.trace(),
                        "An alias was defined for method %s(), which exists in both %s and %s. Use %s::%s or %s::%s to resolve the ambiguity",
                        alias.method(), eligible.get(0).trait().getName(), eligible.get(1).trait().getName(),
                        eligible.get(0).trait().getName(), alias.method(), eligible.get(1).trait().getName(), alias.method());
                source = eligible.isEmpty() ? null : eligible.get(0);
                if (source == null) fail(env, alias.trace(), "An alias was defined for method %s but this method does not exist", alias.method());
            }
            String target = alias.alias() == null ? source.donor().getName() : alias.alias();
            Choice changed = new Choice(source.trait(), source.donor(), target, alias.visibility(), alias.makeFinal());
            if (alias.alias() == null || key(target).equals(key(source.donor().getName()))) {
                Choice winner = winners.get(key(target));
                if (winner != null && winner.donor() == source.donor()) winners.put(key(target), changed);
            } else merge(owner, env, all, changed, alias.trace());
        }
        for (Choice winner : winners.values()) merge(owner, env, all, winner, recipe.trace());
    }
    private static void merge(ClassEntity owner, Environment env, Map<String, Choice> selected, Choice next, TraceInfo trace) {
        MethodEntity own = owner.findMethod(key(next.name()));
        if (own != null && own.getClazz() == owner && own.getTrait() == null) return;
        Choice previous = selected.get(key(next.name()));
        if (previous == null || previous.donor().isAbstract() && !next.donor().isAbstract()) selected.put(key(next.name()), next);
        else if (!previous.donor().isAbstract() && !next.donor().isAbstract() && previous.donor() != next.donor())
            fail(env, trace, "Trait method %s::%s collides with %s::%s", next.trait().getName(), next.name(), previous.trait().getName(), previous.name());
    }

    private static MethodEntity copyMethod(ClassEntity owner, Choice choice) {
        MethodEntity source = choice.donor(), target = source.duplicateForInject();
        target.setClazz(owner); target.setTrait(choice.trait()); target.setName(choice.name());
        if (choice.visibility() != null) target.setModifier(choice.visibility());
        if (choice.makeFinal()) target.setFinal(true);
        target.setGeneratorEntity(source.getGeneratorEntity());
        ClassEntity attributeDonor=source.getClazz();
        String actualAttributeDonor=attributeDonor.getNativeClass()==null ? attributeDonor.getCompiledInternalName() : attributeDonor.getNativeClass().getName();
        target.setPhpAttributes(source.getPhpAttributes().stream().map(attribute->attribute.bindInitializers(
                attributeDonor.getCompiledInternalName(),actualAttributeDonor,owner.getName())).toList());
        ParameterEntity[] parameters = source.getParameters();
        if (parameters != null) {
            ParameterEntity[] cloned = new ParameterEntity[parameters.length];
            for (int i = 0; i < cloned.length; i++) {
                ParameterEntity from = parameters[i], to = new ParameterEntity(from.getContext());
                to.setPhpAttributes(from.getPhpAttributes().stream().map(attribute->attribute.bindInitializers(
                        attributeDonor.getCompiledInternalName(),actualAttributeDonor,owner.getName())).toList());
                to.setName(from.getName()); to.setTrace(from.getTrace()); to.setTypeChecker(from.getTypeChecker());
                to.setNullable(from.isNullable()); to.setReference(from.isReference()); to.setVariadic(from.isVariadic());
                to.setMutable(from.isMutable()); to.setUsed(from.isUsed()); to.setPromoted(from.isPromoted());
                to.setDefaultValue(from.getDefaultValue()); to.setDefaultValueConstName(from.getDefaultValueConstName());
                ParameterEntity.DefaultInitializer initializer = from.getDefaultInitializer();
                if (initializer != null) {
                    String physical = initializer.javaClass();
                    ClassEntity donor = source.getClazz();
                    if (physical.replace('.', '/').equals(donor.getCompiledInternalName().replace('.', '/')) && donor.getNativeClass() != null)
                        physical = donor.getNativeClass().getName();
                    to.setDefaultInitializer(new ParameterEntity.DefaultInitializer(initializer.moduleName(), physical,
                            initializer.method(), owner.getName()));
                }
                cloned[i] = to;
            }
            target.setParameters(cloned);
        }
        return target;
    }
    private static int donorFlags(MethodEntity source) {
        ClassEntity donor = source.getClazz();
        if (donor.getNativeClass() == null || donor.getData() == null)
            throw new LinkageError("A trait method must have its actual physical donor definition");
        try {
            java.lang.reflect.Method physical = donor.getNativeClass().getDeclaredMethod(source.getInternalName(),
                    Environment.class, Memory[].class);
            return physical.getModifiers();
        } catch (NoSuchMethodException missing) {
            throw new LinkageError("Trait method lacks its actual physical member", missing);
        }
    }

    private static void properties(ClassEntity owner, ClassEntity trait, Collection<PropertyEntity> fields, Environment env,
                                   List<PhpTraitCompositionPlan.MethodCopy> copies) {
        for (PropertyEntity candidate : fields) {
            if(owner.isPhpReadonly() && !candidate.isPhpReadonly()) fail(env,owner.getTrace(),
                    "Readonly class %s cannot use trait with a non-readonly property %s::$%s",owner.getName(),trait.getName(),candidate.getName());
            if (candidate.getGetter() != null || candidate.getSetter() != null)
                throw new LinkageError("Deferred trait property hooks require their physical hook method plan");
            Map<String, PropertyEntity> table = candidate.isStatic() ? owner.staticProperties : owner.properties;
            PropertyEntity original = table.get(candidate.getName());
            boolean skip = original != null && (original.getTrait() == trait
                    || original.getTrait() == null && original.getClazz() != owner && original.isPrivate());
            if (original != null && !skip && original.getClazz()==owner) {
                boolean sameType = original.getDeclaredType() == null ? candidate.getDeclaredType() == null
                        : candidate.getDeclaredType() != null && original.getDeclaredType().forTraitComposition(owner)
                            .equivalentTo(candidate.getDeclaredType().forTraitComposition(owner));
                Memory left = original.getDefaultValue(), right = candidate.getDefaultValue();
                boolean sameDefault = left == null ? right == null : right != null && left.identical(right);
                if ((original.getPhpHooks()!=null || candidate.getPhpHooks()!=null) && original!=candidate
                        || original.getModifier() != candidate.getModifier() || original.isStatic() != candidate.isStatic()
                        || original.isFinal() != candidate.isFinal() || original.isPhpReadonly()!=candidate.isPhpReadonly() || !sameType || !sameDefault
                        || original.getDeclaredType() != null && original.isDefault() != candidate.isDefault())
                    fail(env, owner.getTrace(), "%s and %s define the same property ($%s) in the composition of %s. However, the definition differs and is considered incompatible. Class was composed",
                            original.getTrait() == null ? original.getClazz().getName() : original.getTrait().getName(), trait.getName(), candidate.getName(), owner.getName());
            }
            if (original != null && original.getClazz() == owner) continue;
            PropertyEntity copy = candidate.duplicate();copy.setPhpHooks(null);copy.setClazz(owner);copy.setTrait(trait);
            bindTraitAttributes(candidate,copy,owner);
            var catalog=candidate.getPhpHooks();
            if(catalog!=null) {
                List<php.runtime.reflection.support.PhpPropertyHooks.Hook> hooks=new ArrayList<>();
                for(var hook:catalog.declaredOrder()) {
                    if(hook.declaration()!=candidate || hook.method().getClazz()!=candidate.getClazz())
                        throw new LinkageError("Trait hook is not its exact owned property declaration");
                    MethodEntity method=copyMethod(owner,new Choice(trait,hook.method(),hook.method().getName(),null,false));
                    method.setInternalName(method.getName()+"$"+owner.nextMethodIndex());
                    hooks.add(new php.runtime.reflection.support.PhpPropertyHooks.Hook(hook.kind(),copy,method));
                    copies.add(new PhpTraitCompositionPlan.MethodCopy(hook.method(),method,donorFlags(hook.method())));
                }
                copy.setPhpHooks(new php.runtime.reflection.support.PhpPropertyHooks(catalog.isDeclaredVirtual(),hooks));
            }
            owner.addProperty(copy).check(env);
        }
    }
    private static void constants(ClassEntity owner, ClassEntity trait, Environment env) {
        for (ConstantEntity candidate : trait.getConstants()) {
            ConstantEntity original = owner.findConstant(candidate.getName());
            if (original != null && original.getClazz() == owner) {
                boolean sameType = original.getDeclaredType() == null ? candidate.getDeclaredType() == null
                        : candidate.getDeclaredType() != null && original.getDeclaredType().forTraitComposition(owner)
                            .equivalentTo(candidate.getDeclaredType().forTraitComposition(owner));
                Memory left = original.getValue(), right = candidate.getValue();
                if (!sameType || original.getModifier() != candidate.getModifier() || original.isFinal() != candidate.isFinal()
                        || left != null && right != null && !left.identical(right)) owner.traitConstantConflict(env, candidate.getName(), trait.getName());
                if (left == null || right == null)
                    owner.addTraitConstantCheck(candidate.getName(), trait.getName(), right,
                            original.getTraitName() == null ? owner.getName() : original.getTraitName());
                if (original.getTraitName() == null) original.setTraitName(trait.getName());
                continue;
            }
            ConstantEntity copy=candidate.duplicateForTrait(owner,trait.getName());
            bindTraitAttributes(candidate,copy,owner);owner.addConstant(copy).check(env);
        }
    }
    private static void bindTraitAttributes(php.runtime.reflection.support.Entity source,
            php.runtime.reflection.support.Entity target,ClassEntity owner) {
        ClassEntity donor=source instanceof PropertyEntity property ? property.getClazz() : ((ConstantEntity)source).getClazz();
        String physical=donor.getNativeClass()==null ? donor.getCompiledInternalName() : donor.getNativeClass().getName();
        target.setPhpAttributes(source.getPhpAttributes().stream().map(attribute->attribute.bindInitializers(
                donor.getCompiledInternalName(),physical,owner.getName())).toList());
    }
    private static void fail(Environment env, TraceInfo trace, String message, Object... values) {
        env.error(trace, ErrorType.E_COMPILE_ERROR, message, values);
        throw new IllegalStateException("Composition error returned");
    }
}
