package php.runtime.reflection;

import java.io.*;
import php.runtime.env.Environment;
import php.runtime.exceptions.CriticalException;
import php.runtime.loader.dump.ClassDumper;

/** A declaration template never owns the Environment's resolved PHP class or user instances. */
public final class PhpClassDeclaration {
    private PhpClassDeclaration() {}
    /** A single declaration invocation's actual resolved trait occurrences, never stored on runtime entities. */
    public static final class TraitUses {
        private final ClassEntity declaration;
        private final java.util.List<java.util.List<ClassEntity>> groups;
        private TraitUses(ClassEntity declaration,java.util.List<java.util.List<ClassEntity>> groups) {
            this.declaration=declaration;this.groups=java.util.List.copyOf(groups);
        }
    }
    /** Actual lexical declaration/linking effect; composition and metadata loading never call this. */
    public static void beforeTraitUses(ClassEntity owner,Environment env,php.runtime.env.TraceInfo trace) {
        resolveTraitUses(owner,env,trace);
    }
    public static TraitUses resolveTraitUses(ClassEntity owner,Environment env,php.runtime.env.TraceInfo trace) {
        if(owner.getModule()==null || owner.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5)return null;
        var groups=new java.util.ArrayList<java.util.List<ClassEntity>>();
        if(!owner.getDeclarationRelations().traitGroups().isEmpty()) {
            for(PhpClassRelations.TraitGroup group:owner.getDeclarationRelations().traitGroups()) {
                var actual=new java.util.ArrayList<ClassEntity>();
                for(PhpClassRelations.Name name:group.traits()) {
                    ClassEntity trait=PhpClassComposition.resolve(owner,env,name,ClassEntity.Type.TRAIT);
                    actual.add(trait);
                    if(php.runtime.invoke.PhpDeprecatedInvocation.marker(trait)!=null)
                        php.runtime.invoke.PhpDeprecatedInvocation.beforeTrait(env,trace,trait,owner.isAnonymous()?"class@anonymous":owner.getName());
                }
                groups.add(java.util.List.copyOf(actual));
            }
        } else for(ClassEntity trait:owner.getTraits().values())
            if(php.runtime.invoke.PhpDeprecatedInvocation.marker(trait)!=null)
                php.runtime.invoke.PhpDeprecatedInvocation.beforeTrait(env,trace,trait,owner.isAnonymous()?"class@anonymous":owner.getName());
        return new TraitUses(owner,groups);
    }
    /** Pure physical-composition witness for the existing nonordinal anonymous declaration route. */
    public static boolean isBoundAnonymousDeclaration(ClassEntity owner) {
        if(owner==null || !owner.isAnonymous() || owner.isStatic() || owner.isDeferredDeclaration()
                || owner.isDeclarationTemplate() || owner.isUnresolvedDeclaration()
                || owner.getType()!=ClassEntity.Type.CLASS || owner.getModule()==null
                || owner.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5
                || owner.getDeclarationRelations().traitGroups().isEmpty()
                || owner.getClassComposition()==null || owner.getClassComposition().owner()!=owner)
            return false;
        ClassEntity template=owner.getModule().findClass(owner.getName());
        return template!=null && template!=owner && template.getModule()==owner.getModule()
                && template.isAnonymous() && !template.isStatic() && !template.isDeferredDeclaration()
                && template.isDeclarationTemplate() && template.getNativeClass()==null
                && template.getType()==ClassEntity.Type.CLASS && template.getClassComposition()==null
                && java.util.Objects.equals(template.getName(),owner.getName())
                && java.util.Objects.equals(template.getCompiledInternalName(),owner.getCompiledInternalName())
                && java.util.Objects.equals(template.getDeferredParentName(),owner.getDeferredParentName())
                && template.getData()!=null && template.getData().length>0
                && java.util.Arrays.equals(template.getData(),owner.getData())
                && sameSerializedRelations(template.getDeclarationRelations(),owner.getDeclarationRelations());
    }
    public static ClassEntity bind(ClassEntity template,ClassEntity parent,Environment env) {
        return bind(template,parent,env,null);
    }
    public static ClassEntity bind(ClassEntity template,ClassEntity parent,Environment env,TraitUses traits) {
        if(traits!=null && traits.declaration!=template)
            throw new IllegalArgumentException("Resolved trait uses belong to another declaration invocation");
        if ((!template.isDeclarationTemplate() && !template.isUnresolvedDeclaration())
                || template.getNativeClass() != null || template.getModule() == null
                || (template.getDeferredParentName() == null) != (parent == null))
            throw new IllegalArgumentException("Expected a cold class declaration template");
        if (template.isDeferredDeclaration()
                && template.getModule().findDeferredClass(template.getDeclarationId()) != template)
            throw new IllegalArgumentException("Class declaration does not belong to its ordinal catalog");
        if (template.isAnonymous() && template.isDeclarationTemplate()
                && template.getModule().findClass(template.getName()) != template)
            throw new IllegalArgumentException("Anonymous declaration does not belong to its module catalog");
        if (php.runtime.PhpOwnershipExecution.current(env)!=null)
            throw new IllegalArgumentException("Deferred inheritance lacks its complete ownership protocol");
        try {
            // Reuse the complete metadata transport, including signatures/defaults/generators and future attributes.
            // This is a cold declaration operation, never a per-object copy or PHP source compilation.
            var bytes=new ByteArrayOutputStream();
            var dumper=new ClassDumper(template.getContext(),template.getModule(),env,true);
            dumper.save(template,bytes);
            ClassEntity bound=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            if (bound.getModule() != template.getModule()
                    || !bound.getName().equals(template.getName())
                    || !bound.getCompiledInternalName().equals(template.getCompiledInternalName())
                    || !java.util.Arrays.equals(bound.getData(), template.getData()))
                throw new IllegalStateException("Cold class declaration identity changed");
            PhpClassRelations relations = template.getDeclarationRelations();
            if (bound.getDeclarationRelations() == PhpClassRelations.EMPTY) bound.setDeclarationRelations(relations);
            else if (!sameSerializedRelations(bound.getDeclarationRelations(), relations))
                throw new IllegalStateException("Cold class declaration relations changed");
            if (template.isDeclarationTemplate()) {
                if (template.isDeferredDeclaration()) bound.setDeclarationId(template.getDeclarationId());
                bound.markDeclarationBound();
            }
            if (relations.parent() != null && !java.util.Objects.equals(relations.parent().name(), template.getDeferredParentName()))
                throw new IllegalStateException("Class parent differs from its relation recipe");
            if (parent != null) bound.attachDeferredParentMetadata(parent, env);
            restoreOwnMethodOrigins(template, bound);
            restoreOwnAttributeDeclarations(template,bound);
            PhpTraitCompositionPlan composition = PhpClassComposition.compose(bound, env,traits==null?null:traits.groups);
            if (composition != null) bound.setClassComposition(composition);
            if(bound.isEnum()) {
                ClassEntity marker=env.fetchClass(bound.getEnumBackingType()==null?"UnitEnum":"BackedEnum",true,php.runtime.common.PhpLanguageProfile.PHP_8_5);
                if(!bound.isInstanceOf(marker))bound.addInterface(marker).check(env);
            }
            php.runtime.PhpEnums.validateDeclaration(bound,env);
            php.runtime.reflection.support.PhpOverrideValidation.validate(bound,env);
            if (bound.getType() != ClassEntity.Type.INTERFACE
                    || bound.getData() != null && bound.getData().length > 0)
                env.getScope().getClassLoader().loadClass(bound, true);
            bindOwnDefaultInitializers(template, bound);
            bindOwnAttributeInitializers(template,bound);
            return bound;
        } catch (IOException | ReflectiveOperationException error) {throw new CriticalException(error);}
    }
    // TraceInfo uses identity equality; compare precisely the fields carried by writeTrace.
    private static boolean sameSerializedTrace(php.runtime.env.TraceInfo left, php.runtime.env.TraceInfo right) {
        return left == right || left != null && right != null
                && left.getStartLine() == right.getStartLine()
                && left.getStartPosition() == right.getStartPosition()
                && java.util.Objects.equals(left.getFileName(), right.getFileName());
    }
    private static boolean sameSerializedName(PhpClassRelations.Name left, PhpClassRelations.Name right) {
        return left == right || left != null && right != null
                && left.name().equals(right.name()) && sameSerializedTrace(left.trace(), right.trace());
    }
    private static boolean sameSerializedNames(java.util.List<PhpClassRelations.Name> left,
                                               java.util.List<PhpClassRelations.Name> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++)
            if (!sameSerializedName(left.get(i), right.get(i))) return false;
        return true;
    }
    private static boolean sameSerializedAdaptation(PhpClassRelations.Adaptation left,
                                                    PhpClassRelations.Adaptation right) {
        if (!sameSerializedTrace(left.trace(), right.trace())) return false;
        if (left instanceof PhpClassRelations.Alias a && right instanceof PhpClassRelations.Alias b)
            return java.util.Objects.equals(a.trait(), b.trait()) && a.method().equals(b.method())
                    && java.util.Objects.equals(a.alias(), b.alias()) && a.visibility() == b.visibility()
                    && a.makeFinal() == b.makeFinal();
        if (left instanceof PhpClassRelations.Precedence a && right instanceof PhpClassRelations.Precedence b)
            return a.preferredTrait().equals(b.preferredTrait()) && a.method().equals(b.method())
                    && sameSerializedNames(a.excluded(), b.excluded());
        return false;
    }
    private static boolean sameSerializedRelations(PhpClassRelations left, PhpClassRelations right) {
        if (!sameSerializedName(left.parent(), right.parent())
                || left.interfaces().size() != right.interfaces().size()
                || left.traitGroups().size() != right.traitGroups().size()) return false;
        for (int i = 0; i < left.interfaces().size(); i++) {
            PhpClassRelations.InterfaceUse a = left.interfaces().get(i), b = right.interfaces().get(i);
            if (a.kind() != b.kind() || !sameSerializedName(a.name(), b.name())) return false;
        }
        for (int i = 0; i < left.traitGroups().size(); i++) {
            PhpClassRelations.TraitGroup a = left.traitGroups().get(i), b = right.traitGroups().get(i);
            if (!sameSerializedTrace(a.trace(), b.trace()) || !sameSerializedNames(a.traits(), b.traits())
                    || a.adaptations().size() != b.adaptations().size()) return false;
            for (int j = 0; j < a.adaptations().size(); j++)
                if (!sameSerializedAdaptation(a.adaptations().get(j), b.adaptations().get(j))) return false;
        }
        return true;
    }
    private static void restoreOwnMethodOrigins(ClassEntity template, ClassEntity bound) {
        for(PropertyEntity property:bound.getProperties()) if(property.getClazz()==bound && property.getPhpHooks()!=null && !property.getPhpHooks().declaredOrder().isEmpty()) {
            var original=template.findProperty(property.getName());
            if(original==null || original.getClazz()!=template || original.getPhpHooks()==null)
                throw new IllegalStateException("Hook property declaration changed during cold bind");
            for(var hook:property.getPhpHooks().declaredOrder()) if(hook.declaration()==property) {
                var source=hook.kind()==php.runtime.reflection.support.PhpPropertyHooks.Kind.GET?original.getPhpHooks().get():original.getPhpHooks().set();
                if(source==null || !source.method().getInternalName().equals(hook.method().getInternalName()))
                    throw new IllegalStateException("Hook method declaration changed during cold bind");
                hook.method().setGeneratorEntity(source.method().getGeneratorEntity());
            }
        }
        for (MethodEntity target : bound.getOwnedMethods()) {
            MethodEntity source = template.findMethod(target.getLowerName());
            if (source != null && source.getClazz() == template) target.setGeneratorEntity(source.getGeneratorEntity());
        }
    }
    private static void restoreOwnAttributeDeclarations(ClassEntity template,ClassEntity bound) {
        bound.setPhpAttributes(template.getPhpAttributes());
        for(ConstantEntity source:template.getConstants()) if(source.getClazz()==template) {
            var target=bound.findConstant(source.getName());
            if(target==null || target.getClazz()!=bound) throw new IllegalStateException("Attribute constant declaration changed");
            target.setPhpAttributes(source.getPhpAttributes());
        }
        for(PropertyEntity source:template.getProperties()) if(source.getClazz()==template) {
            var target=bound.properties.get(source.getName());
            if(target==null || target.getClazz()!=bound) throw new IllegalStateException("Attribute property declaration changed");
            target.setPhpAttributes(source.getPhpAttributes());
        }
        for(PropertyEntity source:template.getStaticProperties()) if(source.getClazz()==template) {
            var target=bound.staticProperties.get(source.getName());
            if(target==null || target.getClazz()!=bound) throw new IllegalStateException("Attribute static property declaration changed");
            target.setPhpAttributes(source.getPhpAttributes());
        }
        restoreOwnHookAttributes(template,bound);
        for(MethodEntity source:template.getOwnedMethods()) {
            var target=bound.findMethod(source.getLowerName());
            if(target==null || target.getClazz()!=bound) throw new IllegalStateException("Attribute method declaration changed");
            target.setPhpAttributes(source.getPhpAttributes());
            var from=source.getParameters();var to=target.getParameters();
            if((from==null)!=(to==null) || from!=null && from.length!=to.length) throw new IllegalStateException("Attribute parameter declaration count changed");
            if(from!=null) for(int i=0;i<from.length;i++) {
                if(!from[i].getName().equals(to[i].getName())) throw new IllegalStateException("Attribute parameter declaration identity changed");
                to[i].setPhpAttributes(from[i].getPhpAttributes());
            }
        }
    }
    private static void restoreOwnHookAttributes(ClassEntity template,ClassEntity bound) {
        for(PropertyEntity property:template.getProperties()) if(property.getClazz()==template && property.getPhpHooks()!=null && !property.getPhpHooks().declaredOrder().isEmpty()) {
            PropertyEntity target=bound.properties.get(property.getName());
            if(target==null || target.getClazz()!=bound || target.getPhpHooks()==null)
                throw new IllegalStateException("Hook attribute property declaration changed");
            for(var source:property.getPhpHooks().declaredOrder()) {
                var copy=source.kind()==php.runtime.reflection.support.PhpPropertyHooks.Kind.GET?target.getPhpHooks().get():target.getPhpHooks().set();
                if(copy==null || source.declaration()!=property || copy.declaration()!=target
                        || !source.method().getName().equals(copy.method().getName())
                        || !source.method().getInternalName().equals(copy.method().getInternalName()))
                    throw new IllegalStateException("Hook attribute callable declaration changed");
                copy.method().setPhpAttributes(source.method().getPhpAttributes());
                var from=source.method().getParameters();var to=copy.method().getParameters();
                if((from==null)!=(to==null) || from!=null && from.length!=to.length)
                    throw new IllegalStateException("Hook attribute parameter declaration count changed");
                if(from!=null) for(int i=0;i<from.length;i++) {
                    if(!from[i].getName().equals(to[i].getName()))throw new IllegalStateException("Hook attribute parameter identity changed");
                    to[i].setPhpAttributes(from[i].getPhpAttributes());
                }
            }
        }
    }
    private static void bindAttributes(php.runtime.reflection.support.Entity entity,String source,String physical) {
        if(!entity.getPhpAttributes().isEmpty()) entity.setPhpAttributes(entity.getPhpAttributes().stream()
                .map(attribute->attribute.bindInitializers(source,physical,null)).toList());
    }
    private static void bindOwnAttributeInitializers(ClassEntity template,ClassEntity bound) {
        if(bound.getNativeClass()==null) return;
        String source=template.getCompiledInternalName(),physical=bound.getNativeClass().getName();
        bindAttributes(bound,source,physical);
        for(ConstantEntity constant:bound.getConstants()) if(constant.getClazz()==bound) bindAttributes(constant,source,physical);
        for(PropertyEntity property:bound.getProperties()) if(property.getClazz()==bound) bindAttributes(property,source,physical);
        for(PropertyEntity property:bound.getStaticProperties()) if(property.getClazz()==bound) bindAttributes(property,source,physical);
        for(PropertyEntity property:bound.getProperties()) if(property.getClazz()==bound && property.getPhpHooks()!=null && !property.getPhpHooks().declaredOrder().isEmpty())
            for(var hook:property.getPhpHooks().declaredOrder()) {
                bindAttributes(hook.method(),source,physical);
                if(hook.method().getParameters()!=null) for(ParameterEntity parameter:hook.method().getParameters())bindAttributes(parameter,source,physical);
            }
        for(PropertyEntity property:bound.getProperties()) if(property.getClazz()==bound && property.getPhpHooks()!=null && !property.getPhpHooks().declaredOrder().isEmpty())
            for(var hook:property.getPhpHooks().declaredOrder()) if(hook.declaration()==property && hook.method().getTrait()==null) {
                bindAttributes(hook.method(),source,physical);
                if(hook.method().getParameters()!=null)for(ParameterEntity parameter:hook.method().getParameters())bindAttributes(parameter,source,physical);
            }
        for(MethodEntity method:bound.getOwnedMethods()) {
            bindAttributes(method,source,physical);
            if(method.getParameters()!=null) for(ParameterEntity parameter:method.getParameters())bindAttributes(parameter,source,physical);
        }
    }
    private static void bindOwnDefaultInitializers(ClassEntity template, ClassEntity bound) {
        if (bound.getNativeClass() == null) return;
        for (MethodEntity method : bound.getOwnedMethods()) {
            if (method.getTrait() != null || method.getParameters() == null) continue;
            for (ParameterEntity parameter : method.getParameters()) {
                ParameterEntity.DefaultInitializer initializer = parameter.getDefaultInitializer();
                if (initializer != null && initializer.javaClass().replace('.', '/').equals(template.getCompiledInternalName().replace('.', '/')))
                    parameter.setDefaultInitializer(new ParameterEntity.DefaultInitializer(initializer.moduleName(),
                            bound.getNativeClass().getName(), initializer.method(), initializer.declarationScope()));
            }
        }
    }
}
