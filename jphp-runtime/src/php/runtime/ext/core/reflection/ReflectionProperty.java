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
    public final static int IS_READONLY = 128;
    public final static int IS_PROTECTED_SET = 2048;
    public final static int IS_PRIVATE_SET = 4096;
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
        if(getOwnershipObject()==null) {
            getProperties().put("name",new StringMemory(entity.getName()));getProperties().put("class",new StringMemory(entity.getClazz().getName()));
        } else {
            php.runtime.PhpOwnershipReflectionCalls.publishMetadata(this,"name",new StringMemory(entity.getName()));
            php.runtime.PhpOwnershipReflectionCalls.publishMetadata(this,"class",new StringMemory(entity.getClazz().getName()));
        }
    }

    @php.runtime.annotation.Reflection.Signature({
            @php.runtime.annotation.Reflection.Arg(value="name",type=php.runtime.common.HintType.STRING,
                    nullable=true,optional=@php.runtime.annotation.Reflection.Optional("null")),
            @php.runtime.annotation.Reflection.Arg(value="flags",type=php.runtime.common.HintType.INT,
                    optional=@php.runtime.annotation.Reflection.Optional("0"))})
    public Memory getAttributes(Environment env,Memory...args) {
        return ReflectionAttribute.collect(env,entity,args[0],args[1],"ReflectionProperty");
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

    @Signature public Memory isAbstract(Environment env, Memory... args) {
        if(entity.getPhpHooks()!=null) for(var hook:entity.getPhpHooks().declarationOrder())
            if(hook.method().isAbstractable()) return Memory.TRUE;
        return Memory.FALSE;
    }
    @Signature public Memory isVirtual(Environment env, Memory... args) {
        return entity.getPhpHooks()!=null && entity.getPhpHooks().isVirtual() ? Memory.TRUE : Memory.FALSE;
    }
    @Signature public Memory hasHooks(Environment env, Memory... args) {
        return entity.getPhpHooks()==null ? Memory.FALSE : Memory.TRUE;
    }
    @Signature(value=@Arg(value="type",type=HintType.OBJECT,typeClass="PropertyHookType"),result=@Arg(type=HintType.BOOLEAN))
    public Memory hasHook(Environment env,Memory...args) {
        var kind=php.runtime.ext.core.classes.PhpPropertyHookType.kind(env,args[0]);
        var hooks=entity.getPhpHooks();
        return hooks!=null && (kind==php.runtime.reflection.support.PhpPropertyHooks.Kind.GET?hooks.get():hooks.set())!=null ? Memory.TRUE : Memory.FALSE;
    }
    @Signature(value=@Arg(value="type",type=HintType.OBJECT,typeClass="PropertyHookType"),result=@Arg(type=HintType.OBJECT,typeClass="ReflectionMethod",nullable=true))
    public Memory getHook(Environment env,Memory...args) {
        var kind=php.runtime.ext.core.classes.PhpPropertyHookType.kind(env,args[0]);
        var hooks=entity.getPhpHooks();var hook=hooks==null?null:kind==php.runtime.reflection.support.PhpPropertyHooks.Kind.GET?hooks.get():hooks.set();
        return hook==null?Memory.NULL:ObjectMemory.valueOf(new ReflectionMethod(env,hook.method()));
    }
    @Signature public Memory getHooks(Environment env, Memory... args) {
        var result=new php.runtime.memory.ArrayMemory();
        if(entity.getPhpHooks()!=null) for(var hook:entity.getPhpHooks().reflectionOrder())
            result.putAsKeyString(hook.kind()==php.runtime.reflection.support.PhpPropertyHooks.Kind.GET?"get":"set",
                    ObjectMemory.valueOf(new ReflectionMethod(env,hook.method())));
        return result.toConstant();
    }
    private php.runtime.lang.IObject lazyReceiver(Environment env,Memory memory) {
        return php.runtime.PhpLazyObjects.requireReceiver(env,entity.getClazz(),memory,"property");
    }
    @Signature(value=@Arg(value="object",type=HintType.OBJECT),result=@Arg(type=HintType.BOOLEAN))
    public Memory isLazy(Environment env,Memory...args) {
        return php.runtime.PhpLazyObjects.isLazy(lazyReceiver(env,args[0]),entity)?Memory.TRUE:Memory.FALSE;
    }
    @Signature(value=@Arg(value="object",type=HintType.OBJECT),returnType="void")
    public Memory skipLazyInitialization(Environment env,Memory...args) throws Throwable {
        var object=lazyReceiver(env,args[0]);
        if(php.runtime.PhpOwnershipExecution.current(env)!=null)php.runtime.PhpLazyObjects.skipManaged(env,object,entity,Memory.NULL,false,
                php.runtime.PhpOwnershipReflectionCalls.current(env,this,"skipLazyInitialization",args));
        else php.runtime.PhpLazyObjects.skip(env,object,entity,Memory.NULL,false);return Memory.NULL;
    }
    @Signature(value={@Arg(value="object",type=HintType.OBJECT),@Arg(value="value",type=HintType.MIXED)},returnType="void")
    public Memory setRawValueWithoutLazyInitialization(Environment env,Memory...args) throws Throwable {
        var object=lazyReceiver(env,args[0]);
        if(php.runtime.PhpOwnershipExecution.current(env)!=null)php.runtime.PhpLazyObjects.skipManaged(env,object,entity,args[1],true,
                php.runtime.PhpOwnershipReflectionCalls.current(env,this,"setRawValueWithoutLazyInitialization",args));
        else php.runtime.PhpLazyObjects.skip(env,object,entity,args[1],true);return Memory.NULL;
    }

    @Signature(value=@Arg(value="object",type=HintType.OBJECT),result=@Arg(type=HintType.MIXED))
    public Memory getRawValue(Environment env, Memory... args) throws Throwable {
        if(php.runtime.PhpOwnershipExecution.current(env)!=null)return php.runtime.PhpLazyObjects.managedRawValue(env,lazyReceiver(env,args[0]),entity,
                php.runtime.PhpOwnershipReflectionCalls.current(env,this,"getRawValue",args));
        if(entity.getPhpHooks()==null) return getValue(env,args);
        var object=args[0].toValue(ObjectMemory.class);
        if(!object.getReflection().isInstanceOf(entity.getClazz())) exception(env,
                "Given object is not an instance of the class this property was declared in");
        if(entity.getPhpHooks().isVirtual()) php.runtime.PhpPropertyHookAccess.error(env,env.trace(),
                "Must not read from virtual property %s::$%s",entity);
        var backing=php.runtime.PhpLazyObjects.backing(env,object.value,entity.getSpecificName());
        return entity.readInitialized(env,env.trace(),backing.getProperties().getByScalar(entity.getSpecificName())).toImmutable();
    }
    @Signature(value={@Arg(value="object",type=HintType.OBJECT),@Arg(value="value",type=HintType.MIXED)},returnType="void")
    public Memory setRawValue(Environment env, Memory... args) throws Throwable {
        var context=php.runtime.PhpPropertyWriteContext.enter(env,env.trace(),false);
        try {
            if(php.runtime.PhpOwnershipExecution.current(env)!=null) {
                php.runtime.PhpLazyObjects.managedRawAssign(env,lazyReceiver(env,args[0]),entity,args[1],
                        php.runtime.PhpOwnershipReflectionCalls.current(env,this,"setRawValue",args));return Memory.NULL;
            }
            if(entity.getPhpHooks()!=null && entity.getPhpHooks().isVirtual())
                php.runtime.PhpPropertyHookAccess.error(env,env.trace(),entity.getPhpHooks().set()==null
                        ? "Property %s::$%s is read-only" : "Must not write to virtual property %s::$%s",entity);
            // Native reflection writes permit a different object; retain the existing declared typed/raw assignment path.
            if(entity.getPhpHooks()!=null) {
                entity.assignValue(env,env.trace(),args[0].toValue(ObjectMemory.class).value,entity.getSpecificName(),args[1]);
                return Memory.NULL;
            }
            return setValueInternal(env,args);
        } finally {php.runtime.PhpPropertyWriteContext.leave(context);}
    }

    @Signature public Memory hasType(Environment env, Memory... args) {return entity.getDeclaredType() == null ? Memory.FALSE : Memory.TRUE;}
    @Signature public Memory getType(Environment env, Memory... args) {
        return entity.getDeclaredType() == null ? Memory.NULL : ObjectMemory.valueOf(ReflectionType.fromDeclaration(env, entity.getDeclaredType()));
    }
    @Signature public Memory isFinal(Environment env, Memory... args) {return entity.isFinal() ? Memory.TRUE : Memory.FALSE;}
    @Signature public Memory isReadOnly(Environment env, Memory... args) {return entity.isPhpReadonly()?Memory.TRUE:Memory.FALSE;}
    @Signature public Memory isPrivateSet(Environment env,Memory...args){return entity.isPrivateSet()?Memory.TRUE:Memory.FALSE;}
    @Signature public Memory isProtectedSet(Environment env,Memory...args){return entity.isProtectedSet()?Memory.TRUE:Memory.FALSE;}
    @Signature public Memory isPromoted(Environment env, Memory... args) {return entity.isPromoted() ? Memory.TRUE : Memory.FALSE;}
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
        boolean php85 = modern(env);
        // Private declarations retain their own address; other declarations follow the receiver's effective override.
        PropertyEntity property = php85 && !entity.isPrivate() ? object.getReflection().findProperty(entity.getName()) : entity;
        if (property == null) return Memory.FALSE;
        if (php85 && property.getPhpHooks() != null && property.getPhpHooks().isVirtual()) return Memory.TRUE;
        Memory value = object.getProperties().getByScalar(property.getSpecificName());
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
                var object=arg.toValue(ObjectMemory.class).value;
                if(modern(env) && entity.getPhpHooks()!=null
                        && php.runtime.PhpPropertyHookAccess.write(entity,env,env.trace(),object,args[1],null,null))
                    return Memory.NULL;
                entity.assignValue(env,env.trace(),object,entity.getSpecificName(),args[1]);
            } else
                exception(env, "Argument 1 must be object, given %s", arg.getRealType().toString());
        }
        return Memory.NULL;
    }
}
