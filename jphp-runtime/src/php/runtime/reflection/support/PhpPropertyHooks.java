package php.runtime.reflection.support;

import java.util.List;
import java.util.Objects;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;
import php.runtime.reflection.PropertyEntity;

/** Immutable resolved hook catalog. Native PropertyEntity getter/setter remain independent. */
public final class PhpPropertyHooks {
    public enum Kind { GET, SET }
    public record Hook(Kind kind, PropertyEntity declaration, MethodEntity method) {
        public Hook {
            Objects.requireNonNull(kind);Objects.requireNonNull(declaration);Objects.requireNonNull(method);
            validate(kind,declaration,method);
        }
        public void validate() {validate(kind,declaration,method);}
        private static void validate(Kind kind, PropertyEntity declaration, MethodEntity method) {
            if (method.getClazz()!=declaration.getClazz()
                    || !method.getName().equals("$"+declaration.getName()+"::"+(kind==Kind.GET?"get":"set"))
                    || method.isStatic())
                throw new IllegalArgumentException("Hook does not belong to its exact declared property");
            ParameterEntity[] parameters=method.getParameters();
            int count=parameters==null?0:parameters.length;
            if (count!=(kind==Kind.GET?0:1)) throw new IllegalArgumentException("Invalid hook parameter count");
            if (kind==Kind.SET && (method.isReturnReference() || parameters[0].isReference()
                    || parameters[0].isVariadic() || parameters[0].getDefaultValue()!=null))
                throw new IllegalArgumentException("Invalid set hook parameter convention");
        }
    }
    private final boolean virtual;
    private final Hook get;
    private final Hook set;
    private final List<Hook> declarationOrder;
    private final boolean declaredVirtual;
    private final List<Hook> declaredOrder;
    private final PropertyEntity inheritedFrom;

    public PhpPropertyHooks(boolean virtual, List<Hook> declarationOrder) {
        this(virtual,declarationOrder,virtual,declarationOrder,null);
    }
    private PhpPropertyHooks(boolean virtual,List<Hook> bound,boolean declaredVirtual,List<Hook> declared,PropertyEntity inheritedFrom) {
        this.virtual=virtual;this.declarationOrder=List.copyOf(bound);
        this.declaredVirtual=declaredVirtual;this.declaredOrder=List.copyOf(declared);this.inheritedFrom=inheritedFrom;
        for(Hook hook:this.declaredOrder) if(!this.declarationOrder.contains(hook))
            throw new IllegalArgumentException("Declared hook is absent from its bound catalog");
        if (this.declarationOrder.isEmpty() || this.declarationOrder.size()>2)
            throw new IllegalArgumentException("Empty or oversized hook catalog");
        Hook read=null,write=null;
        for (Hook hook : this.declarationOrder) {
            if (hook.kind()==Kind.GET) {if(read!=null) throw new IllegalArgumentException("Duplicate get hook");read=hook;}
            else {if(write!=null) throw new IllegalArgumentException("Duplicate set hook");write=hook;}
        }
        this.get=read;this.set=write;
        if (!virtual && read!=null && read.method().isReturnReference() && write!=null)
            throw new IllegalArgumentException("Backed reference get cannot coexist with set");
    }
    /** Identity admission for hidden hook methods; they are not PHP class-method entries. */
    public static Hook ownedMethod(php.runtime.reflection.ClassEntity owner,MethodEntity method) {
        if(owner==null || method==null || method.getClazz()!=owner) return null;
        for(PropertyEntity property:owner.getProperties()) {
            if(property.getClazz()!=owner || property.getPhpHooks()==null) continue;
            for(Hook hook:property.getPhpHooks().declaredOrder())
                if(hook.declaration()==property && hook.method()==method) {hook.validate();return hook;}
        }
        return null;
    }
    /** Cold composition records its exact parent recipe and the original source declaration subset. */
    static PhpPropertyHooks inherited(PropertyEntity parent,boolean virtual,List<Hook> bound,PhpPropertyHooks own) {
        return new PhpPropertyHooks(virtual,bound,own!=null && own.declaredVirtual,
                own==null?List.of():own.declaredOrder,parent);
    }
    public boolean isVirtual() {return virtual;}
    public boolean isDeclaredVirtual() {return declaredVirtual;}
    public List<Hook> declaredOrder() {return declaredOrder;}
    public boolean isResolved() {return inheritedFrom!=null;}
    public Hook declared(Kind kind) {
        for(Hook hook:declaredOrder) if(hook.kind()==kind) return hook;
        return null;
    }
    public Hook get() {return get;}
    public Hook set() {return set;}
    public List<Hook> declarationOrder() {return declarationOrder;}
    /** Reflection order is GET then SET, independently of source declaration order. */
    public List<Hook> reflectionOrder() {
        return get==null?List.of(set):set==null?List.of(get):List.of(get,set);
    }
    /** A cold decoded owned hook is linked only when its exact property gets its owning class. */
    public void bindOwned(PropertyEntity property) {
        if(property.getClazz()==null) throw new IllegalArgumentException("Unbound hook property");
        for(Hook hook:declarationOrder) {
            if(hook.declaration()==property && hook.method().getClazz()==null)
                hook.method().setClazz(property.getClazz());
            hook.validate();
        }
    }
    /** Source installation precedes relation composition, even when a parent entity is already known. */
    public void validateDeclarationFor(PropertyEntity property) {
        var owner=property.getClazz();
        if(owner==null || owner.getModule()==null
                || owner.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5
                || owner.findProperty(property.getName())!=property || isResolved()
                || declaredOrder.size()!=declarationOrder.size())
            throw new IllegalArgumentException("Expected an exact owned local hook declaration");
        // This permission applies only to this declaration installation. Ordinary/cold consumers
        // still use validateFor; compile completion must prepare the actual effective catalog.
        boolean declaredParent=owner.getParent()!=null || owner.isUnresolvedDeclaration();
        validateFor(property,declaredParent);
    }
    public void validateFor(PropertyEntity property) {validateFor(property,false);}
    private void validateFor(PropertyEntity property,boolean declarationInstallation) {
        // An unbound cold carrier is not callable. The class preflight must prove its pending parent;
        // prepare() decides final storage before the class can be published or instantiated.
        boolean pendingParent = !isResolved() && (declarationInstallation || property.getClazz()==null || property.getClazz().isUnresolvedDeclaration());
        if (property.isStatic() || property.isPhpReadonly() || virtual && property.isDefault() && !pendingParent)
            throw new IllegalArgumentException("Invalid hooked property storage contract");
        // PropertyEntity.duplicate() is a cold unbound carrier; it grants no callable admission.
        if(property.getClazz()==null) {
            for(Hook hook:declarationOrder) {
                hook.validate();
                if(!hook.declaration().getName().equals(property.getName()))
                    throw new IllegalArgumentException("Unbound hook property name mismatch");
            }
            return;
        }
        for(Hook hook:declaredOrder)
            if(hook.declaration()!=property || hook.method().getClazz()!=property.getClazz())
                throw new IllegalArgumentException("Foreign declared hook");
        PhpPropertyHooks parent=null;
        if(inheritedFrom!=null) {
            if(property.getClazz()==null || property.getClazz().getParent()==null || inheritedFrom.isPrivate()
                    || property.getClazz().getParent().findProperty(property.getName())!=inheritedFrom
                    || property.getPrototype()!=inheritedFrom)
                throw new IllegalArgumentException("Inherited hook recipe is not the exact parent property");
            parent=inheritedFrom.getPhpHooks();
            if(virtual!=(declaredVirtual && !declaredOrder.isEmpty() && parent!=null && parent.isVirtual()))
                throw new IllegalArgumentException("Inherited hook storage recipe changed");
        }
        for (Hook hook : declarationOrder) {
            hook.validate();
            if (!hook.declaration().getName().equals(property.getName()))
                throw new IllegalArgumentException("Inherited hook property name mismatch");
            Hook declared=declared(hook.kind());
            Hook selected=parent==null?null:hook.kind()==Kind.GET?parent.get():parent.set();
            if(hook!=declared && (inheritedFrom==null || declared!=null || hook!=selected))
                throw new IllegalArgumentException("Foreign bound hook selection");
        }
        if(parent!=null) for(Hook required:parent.declarationOrder())
            if(declared(required.kind())==null && (!required.method().isAbstractable() || virtual)
                    && (required.kind()==Kind.GET?get:set)!=required)
                throw new IllegalArgumentException("Inherited hook selection omitted its exact parent body");
    }
}
