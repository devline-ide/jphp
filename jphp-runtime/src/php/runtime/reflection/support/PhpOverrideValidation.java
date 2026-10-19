package php.runtime.reflection.support;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.*;

/** Functional Override checks inspect resolved declaration relations without evaluating attribute arguments. */
public final class PhpOverrideValidation {
    private PhpOverrideValidation() {}
    private static boolean marked(Entity entity) {
        return entity.getPhpAttributes().stream().anyMatch(a->a.name().equalsIgnoreCase("Override"));
    }
    private static boolean methodParent(ClassEntity owner,String name) {
        if(owner.getParent()!=null) {
            var candidate=owner.getParent().findMethod(name);
            if(candidate!=null && !candidate.isPrivate())return true;
        }
        for(var contract:owner.getInterfaces().values()) {
            var candidate=contract.findMethod(name);
            if(candidate!=null && !candidate.isPrivate())return true;
        }
        return false;
    }
    private static boolean propertyParent(ClassEntity owner,String name) {
        if(owner.getParent()!=null) {
            var candidate=owner.getParent().findProperty(name);
            if(candidate!=null && !candidate.isPrivate())return true;
        }
        for(var contract:owner.getInterfaces().values()) {
            var candidate=contract.findProperty(name);
            if(candidate!=null && !candidate.isPrivate())return true;
        }
        return false;
    }
    public static void validate(ClassEntity owner,Environment env) {
        if(owner.getModule()==null || owner.getModule().getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5
                || owner.isTrait() || owner.isDeclarationTemplate() || owner.isUnresolvedDeclaration())return;
        for(var method:owner.getOwnedMethods())if(marked(method) && !methodParent(owner,method.getLowerName()))
            env.error(method.getTrace(),ErrorType.E_COMPILE_ERROR,"%s::%s() has #[\\Override] attribute, but no matching parent method exists",owner.getName(),method.getName());
        for(var property:owner.getProperties())if(property.getClazz()==owner && marked(property) && !propertyParent(owner,property.getName()))
            env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,"%s::$%s has #[\\Override] attribute, but no matching parent property exists",owner.getName(),property.getName());
        for(var property:owner.getStaticProperties())if(property.getClazz()==owner && marked(property) && !propertyParent(owner,property.getName()))
            env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,"%s::$%s has #[\\Override] attribute, but no matching parent property exists",owner.getName(),property.getName());
    }
}
