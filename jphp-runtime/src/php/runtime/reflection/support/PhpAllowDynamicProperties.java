package php.runtime.reflection.support;
import java.util.List;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;
/** Declaration-only, inherited dynamic-property policy; no object slots or argument evaluation. */
public final class PhpAllowDynamicProperties {
    private PhpAllowDynamicProperties(){}
    public static boolean marked(ClassEntity entity){
        List<PhpAttribute> attributes=entity.getPhpAttributes();
        for(int i=0;i<attributes.size();i++)if(attributes.get(i).name().equalsIgnoreCase("AllowDynamicProperties"))return true;
        return false;
    }
    public static boolean allows(ClassEntity entity){
        for(ClassEntity owner=entity;owner!=null;owner=owner.getParent())if(marked(owner))return true;
        return false;
    }
    public static String rejection(ClassEntity entity,int target){
        if(target!=PhpAttribute.CLASS){
            String kind=switch(target){case PhpAttribute.FUNCTION->"function";case PhpAttribute.METHOD->"method";
                case PhpAttribute.PROPERTY->"property";case PhpAttribute.CLASS_CONSTANT->"class constant";
                case PhpAttribute.PARAMETER->"parameter";case PhpAttribute.CONSTANT->"constant";
                default->throw new IllegalArgumentException("Invalid attribute target");};
            return "Attribute \"AllowDynamicProperties\" cannot target "+kind+" (allowed targets: class)";
        }
        String kind=entity.isTrait()?"trait":entity.isInterface()?"interface":entity.isPhpReadonly()?"readonly class":entity.isEnum()?"enum":null;
        return kind==null?null:"Cannot apply #[\\AllowDynamicProperties] to "+kind+" "+entity.getName();
    }
    public static String declarationRejection(ClassEntity owner,int target,List<PhpAttribute> attributes){
        int count=0;boolean delayed=false;
        for(PhpAttribute attribute:attributes){
            if(attribute.name().equalsIgnoreCase("AllowDynamicProperties"))count++;
            if(attribute.name().equalsIgnoreCase("DelayedTargetValidation"))delayed=true;
        }
        if(!delayed && target!=PhpAttribute.CLASS)return rejection(owner,target);
        if(count>1)return "Attribute \"AllowDynamicProperties\" must not be repeated";
        return delayed?null:rejection(owner,target);
    }
    public static void validate(Entity declaration,ClassEntity owner,Environment env){
        if(owner.getModule()==null || owner.getModule().getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5)return;
        for(PhpAttribute attribute:declaration.getPhpAttributes())if(attribute.name().equalsIgnoreCase("AllowDynamicProperties")){
            String message=declarationRejection(owner,attribute.target(),declaration.getPhpAttributes());
            if(message!=null)env.error(attribute.trace(),ErrorType.E_COMPILE_ERROR,message);
        }
    }
}
