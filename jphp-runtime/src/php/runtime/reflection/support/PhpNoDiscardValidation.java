package php.runtime.reflection.support;
import java.util.List;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
/** Pure declaration checks; no argument initializer, provider or autoloader runs here. */
public final class PhpNoDiscardValidation {
    private PhpNoDiscardValidation(){}
    public static String rejection(Entity declaration,int target,List<PhpAttribute> attributes){
        int count=0;boolean delayed=false;
        for(PhpAttribute attribute:attributes){
            if(attribute.name().equalsIgnoreCase("NoDiscard"))count++;
            if(attribute.name().equalsIgnoreCase("DelayedTargetValidation"))delayed=true;
        }
        if(count==0)return null;
        if(!delayed && target!=PhpAttribute.FUNCTION && target!=PhpAttribute.METHOD){
            String kind=switch(target){case PhpAttribute.CLASS->"class";case PhpAttribute.PROPERTY->"property";
                case PhpAttribute.CLASS_CONSTANT->"class constant";case PhpAttribute.PARAMETER->"parameter";
                case PhpAttribute.CONSTANT->"constant";default->throw new IllegalArgumentException("Invalid attribute target");};
            return "Attribute \"NoDiscard\" cannot target "+kind+" (allowed targets: function, method)";
        }
        if(count>1)return "Attribute \"NoDiscard\" must not be repeated";
        if(!(declaration instanceof AbstractFunctionEntity function))return null;
        if(function.getReturnTypeChecker()!=null){
            String returns=function.getReturnTypeChecker().getSignature();String kind=target==PhpAttribute.METHOD?"method":"function";
            if(returns.equals("void"))return "A void "+kind+" does not return a value, but #[\\NoDiscard] requires a return value";
            if(returns.equals("never"))return "A never returning "+kind+" does not return a value, but #[\\NoDiscard] requires a return value";
        }
        if(target==PhpAttribute.METHOD && function instanceof MethodEntity method){
            String name=method.getName().toLowerCase(java.util.Locale.ROOT);
            if(name.equals("__construct") || name.equals("__destruct") || name.equals("__clone")
                    || name.equals("__set") || name.equals("__unset") || name.equals("__unserialize"))
                return "Method "+method.getClazzName()+"::"+method.getName()+" cannot be #[\\NoDiscard]";
        }
        return null;
    }
    public static void validate(Entity declaration,ClassEntity owner,Environment env){
        if(owner.getModule()==null || owner.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5)return;
        for(var attribute:declaration.getPhpAttributes())if(attribute.name().equalsIgnoreCase("NoDiscard")){
            String failure=rejection(declaration,attribute.target(),declaration.getPhpAttributes());
            if(failure!=null)env.error(attribute.trace(),ErrorType.E_COMPILE_ERROR,failure);
        }
    }
}
