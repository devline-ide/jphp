package php.runtime.ext.core.classes;
import php.runtime.Memory;
import php.runtime.common.Modifier;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.ClassEntity;
import static php.runtime.annotation.Reflection.*;

/** Native value object; its private value is omitted only from debug presentation. */
@Name("SensitiveParameterValue") @Final
@Signature(@Arg(value="value",typeDeclaration="mixed",modifier=Modifier.PRIVATE,readOnly=true))
public final class PhpSensitiveParameterValue extends BaseObject {
    public PhpSensitiveParameterValue(Environment env,ClassEntity clazz) {super(env,clazz);}
    public PhpSensitiveParameterValue(Environment env,Memory value) {
        this(env,env.getScope().fetchPhp85Class("SensitiveParameterValue"));store(value);
    }
    public static void configure(ClassEntity entity) {
        if(entity.getNativeClass()!=PhpSensitiveParameterValue.class)throw new IllegalArgumentException("Foreign SensitiveParameterValue native declaration");
        var value=entity.findProperty("value");
        value.setDeclaredType(new php.runtime.reflection.support.PhpTypeDeclaration(java.util.List.of(java.util.List.of("mixed"))));
        value.setPhpReadonly(true);value.setDefault(false);value.setDefaultValue(Memory.UNDEFINED);value.setHiddenInDebugInfo(true);
    }
    private void store(Memory value) {
        String key=getReflection().findProperty("value").getSpecificName();
        var current=getProperties().getByScalar(key);
        if(current!=null && !current.isUndefined())getEnvironment().exception(php.runtime.lang.exception.BaseError.class,
                "Cannot modify readonly property SensitiveParameterValue::$value");
        getProperties().putAsKeyString(key,value.toImmutable());
    }
    @Signature(@Arg(value="value",typeDeclaration="mixed"))
    public Memory __construct(Environment env,Memory...args) {store(args[0]);return Memory.NULL;}
    @Signature public Memory getValue() {return getProperties().valueOfIndex(getReflection().findProperty("value").getSpecificName()).toValue().toImmutable();}
    @Signature public Memory __debugInfo() {return new ArrayMemory().toImmutable();}
    @Signature public Memory __serialize(Environment env,Memory...args) {
        env.exception(php.runtime.lang.BaseException.class,"Serialization of 'SensitiveParameterValue' is not allowed");return Memory.NULL;
    }
}
