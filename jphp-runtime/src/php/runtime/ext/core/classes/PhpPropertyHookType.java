package php.runtime.ext.core.classes;

import java.util.List;
import php.runtime.Memory;
import php.runtime.PhpEnums;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.lang.BaseEnum;
import php.runtime.lang.BackedEnum;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.support.PhpPropertyHooks;

/** Native PHP string-backed enum carrier. Its only case factory is the existing per-Environment enum capability. */
@Name("PropertyHookType") @Final @NativePhpSignature
public final class PhpPropertyHookType extends BaseEnum implements BackedEnum {
    public PhpPropertyHookType(Environment env,ClassEntity clazz) {super(env,clazz);}

    @Ignore public static void configure(ClassEntity entity) {
        if(entity.getNativeClass()!=PhpPropertyHookType.class || !entity.isInternal() || entity.getParent()!=null
                || !entity.isInstanceOfAsciiName("UnitEnum") || !entity.isInstanceOfAsciiName("BackedEnum")
                || !entity.getConstants().isEmpty() || !entity.getProperties().isEmpty() || !entity.getStaticProperties().isEmpty())
            throw new IllegalArgumentException("Invalid native PropertyHookType declaration");
        var cases=entity.findMethod("cases");
        if(cases==null || cases.getClazz()!=entity || !cases.isStatic() || cases.getNativeMethod()==null
                || cases.getNativeMethod().getDeclaringClass()!=PhpPropertyHookType.class
                || !cases.getInternalName().equals("__$enum$cases") || !cases.getNativeMethod().getName().equals("__$enum$cases"))
            throw new IllegalArgumentException("Native PropertyHookType lacks its actual canonical cases member");
        for(String methodName:List.of("from","tryFrom")) {
            var method=entity.findMethod(methodName.toLowerCase(java.util.Locale.ROOT));
            if(method==null || method.getClazz()!=entity || !method.isStatic() || method.getNativeMethod()==null
                    || method.getNativeMethod().getDeclaringClass()!=PhpPropertyHookType.class
                    || !method.getInternalName().equals("__$enum$"+methodName.toLowerCase(java.util.Locale.ROOT))
                    || !method.getNativeMethod().getName().equals(method.getInternalName())
                    || method.getParameters()==null || method.getParameters().length!=1)
                throw new IllegalArgumentException("Native PropertyHookType lacks its actual backing lookup member");
            method.getParameters()[0].setTypeChecker(new PhpTypeDeclaration(List.of(List.of("string"),List.of("int"))));
            method.setReturnTypeChecker(new PhpTypeDeclaration(methodName.equals("tryFrom")
                    ?List.of(List.of("static"),List.of("null")):List.of(List.of("static"))));
            method.setReturnTypeNullable(methodName.equals("tryFrom"));
        }
        entity.setType(ClassEntity.Type.ENUM);entity.setEnumBackingType("string");entity.setFinal(true);
        for(String name:List.of("Get","Set")) {
            ConstantEntity declaration=new ConstantEntity(name,StringMemory.valueOf(name.toLowerCase(java.util.Locale.ROOT)),true);declaration.setClazz(entity);
            declaration.setModifier(php.runtime.common.Modifier.PUBLIC);declaration.setEnumCase(true);
            entity.addConstant(declaration).check(null);
        }
        PhpEnums.addProperties(entity,null);
        PhpEnums.validateDeclaration(entity,null);
    }
    @Name("cases") @Signature(result=@Arg(type=HintType.ARRAY))
    public static Memory __$enum$cases(Environment env,Memory... arguments) {return PhpEnums.cases(env,"PropertyHookType");}

    @Name("from") @Signature(value=@Arg(value="value",typeDeclaration="string|int"),result=@Arg(typeClass="static"))
    public static Memory __$enum$from(Environment env,Memory... arguments) {return PhpEnums.from(env,"PropertyHookType",arguments,false);}
    @Name("tryFrom") @Signature(value=@Arg(value="value",typeDeclaration="string|int"),result=@Arg(typeClass="static",nullable=true))
    public static Memory __$enum$tryfrom(Environment env,Memory... arguments) {return PhpEnums.from(env,"PropertyHookType",arguments,true);}

    @Ignore public static PhpPropertyHooks.Kind kind(Environment env,Memory value) {
        Memory actual=value.toValue();
        ClassEntity declaration=env.getScope().fetchPhp85Class("PropertyHookType");
        if(!(actual instanceof ObjectMemory object) || !(object.value instanceof PhpPropertyHookType carrier)
                || object.getReflection()!=declaration || !declaration.isEnum())
            throw new IllegalArgumentException("PropertyHookType argument lacks its exact native enum declaration");
        String name=carrier.enumCaseName();ConstantEntity constant=declaration.findConstant(name);
        if(constant==null || !constant.isEnumCase() || PhpEnums.resolveCase(constant,env).toValue(ObjectMemory.class).value!=object.value)
            throw new IllegalArgumentException("PropertyHookType argument lacks its canonical Environment case identity");
        if(name.equals("Get"))return PhpPropertyHooks.Kind.GET;
        if(name.equals("Set"))return PhpPropertyHooks.Kind.SET;
        throw new IllegalArgumentException("Invalid native PropertyHookType case");
    }
}
