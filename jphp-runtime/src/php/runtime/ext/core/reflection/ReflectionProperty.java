package php.runtime.ext.core.reflection;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.Messages;
import php.runtime.env.Environment;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;

import static php.runtime.annotation.Reflection.*;

@Name("ReflectionProperty")
@Signature({
    @Arg(value = "name", type = HintType.STRING, readOnly = true),
    @Arg(value = "class", type = HintType.STRING, readOnly = true)
})
public class ReflectionProperty extends Reflection implements Reflector {
    public final static int IS_STATIC = 1;
    public final static int IS_FINAL = 32;
    public final static int IS_PUBLIC = 256;
    public final static int IS_PROTECTED = 512;
    public final static int IS_PRIVATE = 1024;

    protected PropertyEntity entity;
    protected boolean hackAccess = false;

    public ReflectionProperty(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }

    public void setEntity(PropertyEntity entity) {
        this.entity = entity;
        getProperties().put("name", new StringMemory(entity.getName()));
        getProperties().put("class", new StringMemory(entity.getClazz().getName()));
    }

    @Signature({@Arg("class"), @Arg("name")})
    public Memory __construct(Environment env, Memory... args){
        ClassEntity classEntity;
        Memory arg = args[0];

        if (arg.isObject()){
            classEntity = arg.toValue(ObjectMemory.class).getReflection();
        } else
            classEntity = env.fetchClass(arg.toString());

        if (classEntity == null){
            exception(env, Messages.ERR_CLASS_NOT_FOUND.fetch(arg));
            return Memory.NULL;
        }

        String prop = args[1].toString();
        PropertyEntity entity = classEntity.findProperty(prop);
        if (entity == null)
            entity = classEntity.findStaticProperty(prop);

        if (entity == null){
            exception(env, Messages.ERR_UNDEFINED_PROPERTY.fetch(classEntity.getName(), prop));
            return Memory.NULL;
        }

        setEntity(entity);
        return Memory.NULL;
    }

    @Override
    @Signature
    public Memory __toString(Environment env, Memory... args) {
        return new StringMemory("TODO");
    }

    @Signature
    public Memory getDeclaringClass(Environment env, Memory... args){
        ReflectionClass clazz = new ReflectionClass(env, env.fetchClass("ReflectionClass"));
        clazz.setEntity(entity.getClazz());
        return new ObjectMemory(clazz);
    }

    @Signature
    public Memory getDocComment(Environment env, Memory... args){
        if (entity.getDocComment() == null)
            return Memory.FALSE;

        return new StringMemory(entity.getDocComment().toString());
    }

    @Signature
    public Memory getModifiers(Environment env, Memory... args){
        return LongMemory.valueOf(PhpReflectionModifiers.propertyFlags(entity,
                PhpReflectionModifiers.modern(env, env.trace())));
    }

    @Signature
    public Memory getName(Environment env, Memory... args){
        return new StringMemory(entity.getName());
    }

    @Signature(@Arg(value = "object", optional = @Optional(value = "NULL")))
    public Memory getValue(Environment env, Memory... args) throws Throwable {
        if (!hackAccess && !modern(env)){
            int access = entity.canAccess(env);
            if (access != 0) {
                exception(env, "Cannot access non-public member %s::$%s",
                        entity.getClazz().getName(), entity.getName());
                return Memory.NULL;
            }
        }

        Memory arg = args[0];
        if (arg.isNull()){
            if (!entity.isStatic())
                exception(env, "Dynamic property %s::$%s cannot get a property value statically",
                        entity.getClazz().getName(), entity.getName());

            Memory value = entity.getClazz().getStaticProperty(
                    env, env.trace(), entity.getName(), true, false, null, null, 0, false);
            return entity.readInitialized(env, env.trace(), value);
        } else {
            if (arg.isObject()){
                if (entity.isStatic())
                    exception(env, "Static property %s::$%s cannot get a property value dynamically",
                            entity.getClazz().getName(), entity.getName());

                ObjectMemory object = arg.toValue(ObjectMemory.class);
                if (modern(env)) return object.getReflection().getReflectedProperty(env, env.trace(), object.value, entity);
                return entity.readInitialized(env, env.trace(), object.getProperties().getByScalar(entity.getSpecificName()));
            } else
                exception(env, "Argument 1 must be object, given %s", arg.getRealType().toString());
        }
        return Memory.NULL;
    }

    @Signature
    public Memory isDefault(Environment env, Memory... args){
        return modern(env) || entity.isDefault() ? Memory.TRUE : Memory.FALSE;
    }

    private boolean modern(Environment env) {
        return entity != null && (entity.getClazz().getModule() != null
                && entity.getClazz().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                || env.getModuleManager().getPhpLanguageProfile(env.trace()) == php.runtime.common.PhpLanguageProfile.PHP_8_5);
    }

    @Signature public Memory hasType(Environment env, Memory... args) {return entity.getDeclaredType() == null ? Memory.FALSE : Memory.TRUE;}
    @Signature public Memory getType(Environment env, Memory... args) {
        return entity.getDeclaredType() == null ? Memory.NULL : ObjectMemory.valueOf(ReflectionType.fromDeclaration(env, entity.getDeclaredType()));
    }
    @Signature public Memory isFinal(Environment env, Memory... args) {return entity.isFinal() ? Memory.TRUE : Memory.FALSE;}
    @Signature public Memory hasDefaultValue(Environment env, Memory... args) {return entity.hasDefaultValue() ? Memory.TRUE : Memory.FALSE;}
    @Signature public Memory getDefaultValue(Environment env, Memory... args) {
        return entity.hasDefaultValue() ? entity.getDefaultValue(env).toImmutable() : Memory.NULL;
    }
    @Signature(@Arg(value = "object", optional = @Optional(value = "NULL")))
    public Memory isInitialized(Environment env, Memory... args) {
        if (entity.isStatic()) return entity.getStaticValue(env, env.trace()).isUndefined() ? Memory.FALSE : Memory.TRUE;
        if (args[0].isNull()) env.exception(env.trace(), php.runtime.lang.exception.BaseTypeError.class,
                "ReflectionProperty::isInitialized(): Argument #1 ($object) must be provided for instance properties");
        if (!args[0].isObject()) env.exception(env.trace(), php.runtime.lang.exception.BaseTypeError.class,
                "ReflectionProperty::isInitialized(): Argument #1 ($object) must be of type ?object, %s given",
                php.runtime.reflection.support.PhpTypeDeclaration.valueType(args[0]));
        var object = args[0].toValue(ObjectMemory.class);
        if (!object.getReflection().isInstanceOf(entity.getClazz())) exception(env,
                "Given object is not an instance of the class this property was declared in");
        Memory value = object.getProperties().getByScalar(entity.getSpecificName());
        return value == null || value.isUndefined() ? Memory.FALSE : Memory.TRUE;
    }

    @Signature
    public Memory isPublic(Environment env, Memory... args){
        return entity.isPublic() ? Memory.TRUE : Memory.FALSE;
    }

    @Signature
    public Memory isPrivate(Environment env, Memory... args){
        return entity.isPrivate() ? Memory.TRUE : Memory.FALSE;
    }

    @Signature
    public Memory isProtected(Environment env, Memory... args){
        return entity.isProtected() ? Memory.TRUE : Memory.FALSE;
    }

    @Signature
    public Memory isStatic(Environment env, Memory... args){
        return entity.isStatic() ? Memory.TRUE : Memory.FALSE;
    }

    @Signature(@Arg("accessible"))
    public Memory setAccessible(Environment env, Memory... args){
        hackAccess = args[0].toBoolean();
        return Memory.NULL;
    }

    @Signature({@Arg("object"), @Arg(value="value", optional=@Optional(undefined=true))})
    public Memory setValue(Environment env, Memory... args) throws Throwable {
        var context=php.runtime.PhpPropertyWriteContext.enter(env, env.trace(), false);
        try {return setValueInternal(env, args);}
        finally {php.runtime.PhpPropertyWriteContext.leave(context);}
    }

    private Memory setValueInternal(Environment env, Memory... args) throws Throwable {
        if (!hackAccess && !modern(env)){
            int access = entity.canAccess(env);
            if (access != 0) {
                exception(env, "Cannot access non-public member %s::$%s",
                        entity.getClazz().getName(), entity.getName());
            }
        }

        Memory arg = args[0];
        if (entity.isStatic()){
            Memory assigned=args[1];
            if (modern(env) && assigned.isUndefined()) {
                env.error(env.trace(),php.runtime.exceptions.support.ErrorType.E_DEPRECATED,
                        "Calling ReflectionProperty::setValue() with a single argument is deprecated");
                assigned=arg;arg=Memory.NULL;
            }
            if (!arg.isNull() && !modern(env))
                exception(env, "Static property %s::$%s cannot get a property value dynamically",
                        entity.getClazz().getName(), entity.getName());

            entity.getClazz().getStaticProperty(env, env.trace(), entity.getName(), true, false, null, null, 0, false).assign(assigned);
        } else {
            if (arg.isObject()){
                entity.assignValue(env,env.trace(),arg.toValue(ObjectMemory.class).value,entity.getSpecificName(),args[1]);
            } else
                exception(env, "Argument 1 must be object, given %s", arg.getRealType().toString());
        }
        return Memory.NULL;
    }
}
