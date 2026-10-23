package php.runtime.reflection.support;
import java.util.List;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;

/** Pure builtin declaration checks; no provider, argument initializer or autoloader runs here. */
public final class PhpDeprecatedValidation {
    private PhpDeprecatedValidation(){}
    public static String classRejection(ClassEntity owner,int target){
        if(target!=PhpAttribute.CLASS || owner==null || owner.isTrait())return null;
        String kind=owner.isInterface()?"interface":owner.isEnum()?"enum":"class";
        return "Cannot apply #[\\Deprecated] to "+kind+" "+owner.getName();
    }
    public static String rejection(Entity declaration,int target,List<PhpAttribute> attributes){
        int count=0;boolean delayed=false;
        for(PhpAttribute attribute:attributes){
            if(attribute.name().equalsIgnoreCase("Deprecated"))count++;
            if(attribute.name().equalsIgnoreCase("DelayedTargetValidation"))delayed=true;
        }
        if(count==0)return null;
        int allowed=PhpAttribute.CLASS|PhpAttribute.FUNCTION|PhpAttribute.METHOD|PhpAttribute.CLASS_CONSTANT|PhpAttribute.CONSTANT;
        if(!delayed && (allowed & target)==0){
            String kind=switch(target){case PhpAttribute.PROPERTY->"property";case PhpAttribute.PARAMETER->"parameter";
                default->throw new IllegalArgumentException("Invalid Deprecated attribute target");};
            return "Attribute \"Deprecated\" cannot target "+kind+" (allowed targets: class, function, method, class constant, constant)";
        }
        if(count>1)return "Attribute \"Deprecated\" must not be repeated";
        return delayed?null:classRejection(declaration instanceof ClassEntity owner?owner:null,target);
    }
    public static void validate(Entity declaration,ClassEntity owner,Environment env){
        if(owner.getModule()==null || owner.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5)return;
        for(PhpAttribute attribute:declaration.getPhpAttributes())if(attribute.name().equalsIgnoreCase("Deprecated")){
            String failure=rejection(declaration,attribute.target(),declaration.getPhpAttributes());
            if(failure!=null)env.error(attribute.trace(),ErrorType.E_COMPILE_ERROR,failure);
        }
    }
}
