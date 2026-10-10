package php.runtime.reflection.support;

import php.runtime.common.PhpClassNames;

import php.runtime.common.HintType;
import php.runtime.common.Messages;
import php.runtime.env.Environment;
import php.runtime.exceptions.FatalException;
import php.runtime.exceptions.CompileException;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.ParameterEntity;

import java.util.Locale;

/** PHP 8 atomic type variance. Called after all module classes have been compiled or loaded. */
public final class PhpMethodCompatibility {
    private PhpMethodCompatibility() {}

    public static void validate(ModuleEntity module, Environment env) {
        for (ClassEntity clazz : module.getClasses()) {
            ClassEntity parent = clazz.getParent();
            if (parent != null) {
                for (MethodEntity method : parent.getMethods().values()) {
                    if (method.isPrivate()) continue;
                    // Concrete constructors do not constrain a child's signature.
                    if (method.getLowerName().equals("__construct") && !method.isAbstractable()) continue;
                    check(clazz.findMethod(method.getLowerName()), method, module, env);
                }
            }
            for (ClassEntity contract : clazz.getInterfaces().values()) {
                for (MethodEntity method : contract.getMethods().values()) {
                    MethodEntity impl = clazz.findMethod(method.getLowerName());
                    if (impl != null && !impl.isPublic()) {
                        throw new FatalException("Access level to " + impl.getSignatureString(false)
                                + " must be public (as in interface " + contract.getName() + ")", impl.getTrace());
                    }
                    check(impl, method, module, env);
                }
            }
        }
    }

    private static void check(MethodEntity child, MethodEntity parent, ModuleEntity module, Environment env) {
        if (child == null || child == parent) return;
        if (!compatible(child, parent, module, env)) {
            throw new CompileException(Messages.ERR_INVALID_METHOD_SIGNATURE.fetch(
                    child.getSignatureString(true), parent.getSignatureString(true)), child.getTrace());
        }
    }

    public static boolean compatible(MethodEntity child, MethodEntity parent, ModuleEntity module, Environment env) {
        if (parent.isReturnReference() && !child.isReturnReference()) return false;
        ParameterEntity[] from = parameters(parent), to = parameters(child);
        boolean parentVariadic = variadic(from), childVariadic = variadic(to);
        if (required(to) > required(from) || (parentVariadic && !childVariadic)
                || (!childVariadic && to.length < from.length)) return false;

        for (int i = 0; i < Math.max(from.length, to.length); i++) {
            ParameterEntity parameter = at(from, i), implementation = at(to, i);
            if (parameter == null) break;
            if (implementation == null || parameter.isReference() != implementation.isReference()) return false;
            if (!subtype(parameter.getTypeChecker(), parameter.isNullableOrDefaultNull(), parent.getClazz(),
                    implementation.getTypeChecker(), implementation.isNullableOrDefaultNull(), child.getClazz(), module, env)) {
                return false;
            }
        }

        // An undeclared return type imposes no constraint, including on void returns.
        return parent.getReturnTypeChecker() == null || (child.getReturnTypeChecker() != null
                && subtype(child.getReturnTypeChecker(), child.isReturnTypeNullable(), child.getClazz(),
                parent.getReturnTypeChecker(), parent.isReturnTypeNullable(), parent.getClazz(), module, env));
    }

    private static ParameterEntity[] parameters(MethodEntity method) {
        return method.getParameters() == null ? new ParameterEntity[0] : method.getParameters();
    }

    private static boolean variadic(ParameterEntity[] parameters) {
        return parameters.length != 0 && parameters[parameters.length - 1].isVariadic();
    }

    private static ParameterEntity at(ParameterEntity[] parameters, int index) {
        if (index < parameters.length) return parameters[index];
        return variadic(parameters) ? parameters[parameters.length - 1] : null;
    }

    private static int required(ParameterEntity[] parameters) {
        int required = 0;
        for (int i = 0; i < parameters.length; i++) {
            if (!parameters[i].isVariadic() && parameters[i].getDefaultValue() == null) required = i + 1;
        }
        return required;
    }

    private static boolean top(TypeChecker type) {
        return type == null || (type instanceof TypeChecker.Simple
                && (((TypeChecker.Simple) type).getType() == HintType.ANY
                || ((TypeChecker.Simple) type).getType() == HintType.MIXED));
    }

    private static String name(TypeChecker type, ClassEntity owner) {
        String name = type.getSignature();
        if (name.equalsIgnoreCase("self")) return owner.getName();
        if (name.equalsIgnoreCase("parent") && owner.getParent() != null) return owner.getParent().getName();
        return name;
    }

    private static boolean subtype(TypeChecker sub, boolean subNullable, ClassEntity subOwner,
                                   TypeChecker sup, boolean supNullable, ClassEntity supOwner,
                                   ModuleEntity module, Environment env) {
        if (sub instanceof PhpTypeDeclaration || sup instanceof PhpTypeDeclaration) {
            if (sub != null && sub.getSignature().equals("never")) return true;
            if (sup != null && sup.getSignature().equals("mixed") && sub != null && sub.getSignature().equals("void")) return false;
            return PhpTypeDeclaration.fromChecker(sub, subNullable).isSubtypeOf(
                    PhpTypeDeclaration.fromChecker(sup, supNullable), subOwner, supOwner, module, env);
        }
        if (sub instanceof TypeChecker.Simple && ((TypeChecker.Simple) sub).getType() == HintType.NEVER) return true;
        if (top(sup)) {
            // void means no value, rather than a member of mixed's value set.
            return !(sub instanceof TypeChecker.Simple) || ((TypeChecker.Simple) sub).getType() != HintType.VOID;
        }
        if (top(sub) || (subNullable && !supNullable)) return false;
        String subName = name(sub, subOwner), supName = name(sup, supOwner);
        boolean subStatic = sub instanceof TypeChecker.Simple && ((TypeChecker.Simple) sub).getType() == HintType.STATIC;
        boolean supStatic = sup instanceof TypeChecker.Simple && ((TypeChecker.Simple) sup).getType() == HintType.STATIC;
        if (supStatic) return subStatic && subOwner.isInstanceOf(supOwner);
        if (subStatic) subName = subOwner.getName();
        if (PhpClassNames.canonicalKey(subName).equals(PhpClassNames.canonicalKey(supName))) return true;

        boolean subClass = subStatic || !sub.isBuiltin(), supClass = !sup.isBuiltin();
        if (supName.equals("object")) return subClass;
        if (supName.equals("callable") && subClass) {
            ClassEntity entity = resolve(subName, subOwner, module, env);
            return entity != null && entity.isInstanceOfLower("closure");
        }
        if (supName.equals("iterable")) {
            if (subName.equals("array")) return true;
            ClassEntity entity = subClass ? resolve(subName, subOwner, module, env) : null;
            return entity != null && entity.isInstanceOfLower("traversable");
        }
        if (subClass && supClass) {
            ClassEntity entity = resolve(subName, subOwner, module, env);
            return entity != null && PhpClassNames.matches(entity, supName, env);
        }
        return false;
    }

    private static ClassEntity resolve(String name, ClassEntity owner, ModuleEntity module, Environment env) {
        if (PhpClassNames.canonicalKey(owner.getName()).equals(PhpClassNames.canonicalKey(name))) return owner;
        ClassEntity entity = module.findClass(name);
        if (entity == null && owner.getModule() != null) entity = owner.getModule().findClass(name);
        return entity != null ? entity : env.fetchClass(name, false, php.runtime.common.PhpLanguageProfile.PHP_8_5);
    }
}
