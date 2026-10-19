package php.runtime.reflection.support;

import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.ModuleEntity;

/** Covariant declaration checks after all classes in a source or dump have been linked. */
public final class PhpConstantTypes {
    private PhpConstantTypes() {}
    public static void validate(ModuleEntity module, Environment env) {
        for (ClassEntity owner : module.getClasses()) if (!owner.isUnresolvedDeclaration()) validateClass(owner,module,env);
    }
    public static void validateClass(ClassEntity owner, ModuleEntity module, Environment env) {
        if (owner.getParent() != null) for (ConstantEntity parent : owner.getParent().getConstants())
            if (!parent.isPrivate()) check(owner.findConstant(parent.getName()), parent, module, env);
        for (ClassEntity contract : owner.getInterfaces().values()) for (ConstantEntity parent : contract.getConstants())
            check(owner.findConstant(parent.getName()), parent, module, env);
    }

    private static void check(ConstantEntity child, ConstantEntity parent, ModuleEntity module, Environment env) {
        if (child == null || child == parent || parent.getDeclaredType() == null) return;
        PhpTypeDeclaration actual = child.getDeclaredType(), required = parent.getDeclaredType();
        if (actual != null && actual.isSubtypeOf(required, child.getClazz(), parent.getClazz(), module, env)) return;
        env.error(child.getTrace(), ErrorType.E_COMPILE_ERROR,
                "Type of %s::%s must be compatible with %s::%s of type %s", child.getClazz().getName(), child.getName(),
                parent.getClazz().getName(), parent.getName(), required.bind(parent.getClazz()).getErrorSignature());
    }
}
