package php.runtime.reflection.support;

import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.PropertyEntity;
import java.util.ArrayList;

/** Invariant ordinary property contracts, checked after source/dump linking. */
public final class PhpPropertyTypes {
    private PhpPropertyTypes() {}
    public static void validate(ModuleEntity module, Environment env) {
        for (ClassEntity owner : module.getClasses()) if (!owner.isUnresolvedDeclaration()) validateClass(owner,env);
    }
    public static void validateClass(ClassEntity owner, Environment env) {
        PhpPropertyHookInheritance.prepare(owner,env);
        for(PropertyEntity property:owner.getProperties())if(property.getClazz()==owner)php.runtime.PhpPropertyAccess.validate(property,env);
        for(PropertyEntity property:owner.getStaticProperties())if(property.getClazz()==owner)php.runtime.PhpPropertyAccess.validate(property,env);
        PhpPropertyHookInterfaces.validate(owner,env);
        ClassEntity parent = owner.getParent();
        if(parent!=null && owner.isPhpReadonly()!=parent.isPhpReadonly()) env.error(owner.getTrace(),ErrorType.E_COMPILE_ERROR,
                owner.isPhpReadonly()?"Readonly class %s cannot extend non-readonly class %s":"Non-readonly class %s cannot extend readonly class %s",owner.getName(),parent.getName());
        if (parent == null) return;
        var contracts = new ArrayList<>(parent.getProperties());
        contracts.addAll(parent.getStaticProperties());
        for (PropertyEntity required : contracts) {
            if (required.isPrivate()) continue;
            PropertyEntity actual = required.isStatic() ? owner.findStaticProperty(required.getName()) : owner.findProperty(required.getName());
            if (actual == null || actual == required || actual.getClazz() != owner) continue;
            if(required.isPhpReadonly()!=actual.isPhpReadonly()) env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,
                    required.isPhpReadonly()?"Cannot redeclare readonly property %s::$%s as non-readonly %s::$%s":"Cannot redeclare non-readonly property %s::$%s as readonly %s::$%s",
                    required.getClazz().getName(),required.getName(),owner.getName(),actual.getName());
            if (required.isFinal()) env.error(actual.getTrace(), ErrorType.E_COMPILE_ERROR,
                    "Cannot override final property %s::$%s", required.getClazz().getName(), required.getName());
            if(php.runtime.PhpPropertyAccess.rank(actual.effectiveSetModifier())>php.runtime.PhpPropertyAccess.rank(required.effectiveSetModifier()))
                env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,"Set access level of %s::$%s must be %s (as in class %s)"
                        +(required.effectiveSetModifier()==php.runtime.common.Modifier.PROTECTED?" or weaker":""),
                        owner.getName(),actual.getName(),required.effectiveSetModifier()==php.runtime.common.Modifier.PUBLIC?"omitted":
                        required.effectiveSetModifier()==php.runtime.common.Modifier.PROTECTED?"protected(set)":"private(set)",required.getClazz().getName());
            PhpTypeDeclaration type = actual.getDeclaredType(), contract = required.getDeclaredType();
            var hooks=required.getPhpHooks();
            boolean readOnly=hooks!=null && hooks.isVirtual() && hooks.get()!=null && hooks.set()==null;
            boolean writeOnly=hooks!=null && hooks.isVirtual() && hooks.get()==null && hooks.set()!=null;
            if(readOnly || writeOnly) {
                var childType=type==null?PhpTypeDeclaration.fromChecker(null,false):type;
                var parentType=contract==null?PhpTypeDeclaration.fromChecker(null,false):contract;
                boolean valid=readOnly?childType.isSubtypeOf(parentType,owner,required.getClazz(),owner.getModule(),env)
                        :parentType.isSubtypeOf(childType,required.getClazz(),owner,owner.getModule(),env);
                if(!valid)env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,
                        "Type of %s::$%s must be %s (as in class %s)",owner.getName(),actual.getName(),parentType.getErrorSignature(),required.getClazz().getName());
            } else if (contract == null) {
                if (type != null) env.error(actual.getTrace(), ErrorType.E_COMPILE_ERROR,
                        "Type of %s::$%s must be omitted to match the parent definition in class %s",
                        owner.getName(), actual.getName(), required.getClazz().getName());
            } else if (type == null || !type.bind(owner).equivalentTo(contract.bind(required.getClazz()))) {
                env.error(actual.getTrace(), ErrorType.E_COMPILE_ERROR,
                        "Type of %s::$%s must be %s (as in class %s)", owner.getName(), actual.getName(),
                        contract.getErrorSignature(), required.getClazz().getName());
            }
        }
    }

}
