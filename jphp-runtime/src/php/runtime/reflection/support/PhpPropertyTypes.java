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
        for (ClassEntity owner : module.getClasses()) {
            ClassEntity parent = owner.getParent();
            if (parent == null) continue;
            var contracts = new ArrayList<>(parent.getProperties());
            contracts.addAll(parent.getStaticProperties());
            for (PropertyEntity required : contracts) {
                if (required.isPrivate()) continue;
                PropertyEntity actual = required.isStatic() ? owner.findStaticProperty(required.getName()) : owner.findProperty(required.getName());
                if (actual == null || actual == required || actual.getClazz() != owner) continue;
                if (required.isFinal()) env.error(actual.getTrace(), ErrorType.E_COMPILE_ERROR,
                        "Cannot override final property %s::$%s", required.getClazz().getName(), required.getName());
                PhpTypeDeclaration type = actual.getDeclaredType(), contract = required.getDeclaredType();
                if (contract == null) {
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
}
