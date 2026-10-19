package php.runtime.ext.core.classes;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.reflection.ClassEntity;
import static php.runtime.annotation.Reflection.*;
@Name("Attribute") @Final
@Signature(@Arg(value="flags",type=php.runtime.common.HintType.INT))
public final class PhpAttributeMarker extends BaseObject {
    public static final int TARGET_CLASS=1,TARGET_FUNCTION=2,TARGET_METHOD=4,TARGET_PROPERTY=8,
            TARGET_CLASS_CONSTANT=16,TARGET_PARAMETER=32,TARGET_CONSTANT=64,TARGET_ALL=127,IS_REPEATABLE=128;
    public PhpAttributeMarker(Environment env,ClassEntity clazz) {super(env,clazz);}
    @Signature(@Arg(value="flags",type=php.runtime.common.HintType.INT,optional=@Optional("127")))
    public Memory __construct(Environment env,Memory...args) {setProp("flags",args[0]);return Memory.NULL;}
}
