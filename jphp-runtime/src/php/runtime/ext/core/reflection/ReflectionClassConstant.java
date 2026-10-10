package php.runtime.ext.core.reflection;

import php.runtime.Memory;
import php.runtime.annotation.Reflection.Arg;
import php.runtime.annotation.Reflection.Name;
import php.runtime.annotation.Reflection.Signature;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.memory.LongMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.DocumentComment;

@Name("ReflectionClassConstant")
@Signature({
        @Arg(value = "name", type = HintType.STRING, readOnly = true),
        @Arg(value = "class", type = HintType.STRING, readOnly = true)
})
public class ReflectionClassConstant extends BaseObject {
    public final static int IS_FINAL = 32;
    public final static int IS_PUBLIC = 1 ;
    public final static int IS_PROTECTED = 2 ;
    public final static int IS_PRIVATE = 4 ;

    private ConstantEntity entity;

    public ReflectionClassConstant(Environment env, ConstantEntity constantEntity) {
        super(env);

        this.entity = constantEntity;

        setProp("name", constantEntity.getName());
        setProp("class", constantEntity.getClazz().getName());
    }

    public ReflectionClassConstant(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }

    @Signature({
            @Arg("class"),
            @Arg("name"),
    })
    public Memory __construct(Environment env, Memory... args) {
        boolean modern = env.getModuleManager().getPhpLanguageProfile(env.trace()) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        String className = modern && args[0].isObject() ? args[0].toValue(php.runtime.memory.ObjectMemory.class).getReflection().getName() : args[0].toString();
        String constName = args[1].toString();

        setProp("class", className);
        setProp("name", constName);

        ClassEntity classEntity = env.fetchClass(className);
        if (classEntity == null) {
            env.exception(ReflectionException.class, "Class \"%s\" does not exist", className);
            return Memory.UNDEFINED;
        }
        if (modern) className = classEntity.getName();
        entity = classEntity.findConstant(constName);

        if (entity == null) {
            env.exception(ReflectionException.class, modern ? "Constant %s::%s does not exist" : "Class Constant %s::%s does not exist", className, constName);
        }
        setProp("class", entity.getClazz().getName());

        return Memory.UNDEFINED;
    }

    @Signature
    public String getName() { return entity.getName(); }

    @Signature
    public Memory getDocComment(Environment env) {
        String comment = getDocComment();
        return comment == null ? (env.getModuleManager().getPhpLanguageProfile(env.trace()) == php.runtime.common.PhpLanguageProfile.PHP_8_5 ? Memory.FALSE : Memory.NULL) : StringMemory.valueOf(comment);
    }

    public String getDocComment() {
        return entity.getDocComment() == null ? null : entity.getDocComment().toString();
    }

    @Signature
    public int getModifiers() {
        int mod = 0;

        if (entity.isPrivate())
            mod |= IS_PRIVATE;
        else if (entity.isProtected())
            mod |= IS_PROTECTED;
        else if (entity.isPublic())
            mod |= IS_PUBLIC;

        if (entity.isFinal()) mod |= IS_FINAL;
        return mod;
    }

    @Signature
    public boolean hasType() { return entity.getDeclaredType() != null; }

    @Signature
    public Memory getType(Environment env) {
        return entity.getDeclaredType() == null ? Memory.NULL : php.runtime.memory.ObjectMemory.valueOf(
                ReflectionType.fromDeclaration(env, entity.getDeclaredType()));
    }

    @Signature
    public boolean isFinal() { return entity.isFinal(); }

    @Signature
    public boolean isPrivate() {
        return entity.isPrivate();
    }

    @Signature
    public boolean isProtected() {
        return entity.isProtected();
    }

    @Signature
    public boolean isPublic() {
        return entity.isPublic();
    }

    @Signature
    public Memory getValue(Environment env) {
        return entity.getValue(env).toImmutable();
    }

    @Signature
    public ReflectionClass getDeclaringClass(Environment env) {
        ReflectionClass reflectionClass = new ReflectionClass(env);
        reflectionClass.setEntity(entity.getClazz());
        return reflectionClass;
    }

    @Signature
    public Memory __toString(Environment env, Memory... args) {
        return StringMemory.valueOf(
                "Constant [ " + entity.getModifier().name().toLowerCase() + " " + entity.getName() + " ] { " + entity.getValue(env).toString() + " }"
        );
    }
}
