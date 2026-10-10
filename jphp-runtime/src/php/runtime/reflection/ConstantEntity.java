package php.runtime.reflection;

import php.runtime.Memory;
import php.runtime.common.Modifier;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.reflection.support.Entity;

public class ConstantEntity extends Entity {

    protected ModuleEntity module;
    protected ClassEntity clazz;
    protected Modifier modifier = Modifier.PUBLIC;
    private boolean isFinal;
    private String traitName;
    private String initializerClass;
    private String initializerMethod;
    private volatile java.lang.reflect.Method nativeInitializer;
    private php.runtime.reflection.support.PhpTypeDeclaration declaredType;
    public php.runtime.reflection.support.PhpTypeDeclaration getDeclaredType() {return declaredType;}
    public void setDeclaredType(php.runtime.reflection.support.PhpTypeDeclaration type) {declaredType = type;}
    public Memory validateValue(Environment env, Memory value, ClassEntity owner, boolean compileTime) {
        if (declaredType == null) return value;
        Memory valid = declaredType.strictValue(env, value, owner);
        if (valid != null) return valid;
        String type = declaredType.bind(owner).getErrorSignature();
        String given = php.runtime.reflection.support.PhpTypeDeclaration.valueType(value);
        if (compileTime) env.error(trace, php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR,
                "Cannot use %s as value for class constant %s::%s of type %s", given, owner.getName(), name, type);
        else env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                "Cannot assign %s to class constant %s::%s of type %s", given, owner.getName(), name, type);
        return Memory.NULL;
    }

    public String getInitializerClass() { return initializerClass; }
    public String getInitializerMethod() { return initializerMethod; }
    public void setInitializer(String className, String methodName) {
        initializerClass = className; initializerMethod = methodName; nativeInitializer = null;
    }

    public Memory evaluateInitializer(Environment env, ClassEntity owner) {
        if (value != null) return value;
        if (initializerMethod == null) return getValue(env);
        java.lang.reflect.Method method = nativeInitializer;
        if (method == null) {
            ClassEntity source = initializerClass.equals(clazz.getName()) ? clazz : env.fetchClass(initializerClass);
            try {
                method = source.getNativeClass().getDeclaredMethod(initializerMethod, Environment.class);
                nativeInitializer = method;
            } catch (NoSuchMethodException error) { throw new php.runtime.exceptions.CriticalException(error); }
        }
        env.pushCall(trace, null, null, "{constant-expression}", owner.getName(), owner.getName());
        php.runtime.PhpConstantInitializer.enter(env);
        try { return (Memory) method.invoke(null, env); }
        catch (java.lang.reflect.InvocationTargetException error) { return env.__throwException(error); }
        catch (IllegalAccessException error) { throw new php.runtime.exceptions.CriticalException(error); }
        finally { php.runtime.PhpConstantInitializer.leave(env); env.popCall(); }
    }

    public boolean isFinal() { return isFinal; }
    public void setFinal(boolean value) { isFinal = value; }
    public String getTraitName() { return traitName; }
    public void setTraitName(String name) { traitName = name; }

    public ConstantEntity duplicateForTrait(ClassEntity owner, String traitName) {
        ConstantEntity copy = new ConstantEntity(name, value, caseSensitise);
        copy.setContext(context);
        copy.setTrace(trace);
        copy.setModifier(modifier);
        copy.setDocComment(docComment);
        copy.setFinal(isFinal);
        copy.setDeclaredType(declaredType);
        copy.setTraitName(traitName);
        copy.setInitializer(initializerClass, initializerMethod);
        copy.setClazz(owner);
        return copy;
    }

    protected DocumentComment docComment;
    protected Memory value;
    public final boolean caseSensitise;

    public ConstantEntity(Context context) {
        super(context);
        this.caseSensitise = true;
        this.name = null;
    }

    public ConstantEntity(String name, Memory value, boolean caseSensitise) {
        super(null);
        this.setName(name);
        this.value = value;
        this.caseSensitise = caseSensitise;
    }

    public boolean isDeprecated(){
        return false; // TODO
    }

    public DocumentComment getDocComment() {
        return docComment;
    }

    public void setDocComment(DocumentComment docComment) {
        this.docComment = docComment;
    }

    public ClassEntity getClazz() {
        return clazz;
    }

    @Override
    public void setName(String name) {
        super.setName(name);
        updateInternalName();
    }

    protected void updateInternalName(){
        if (name != null){
            if (clazz != null) {
                boolean modern = module != null && module.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
                internalName = "\0" + php.runtime.common.PhpClassNames.canonicalKey(clazz.getName()) + "##"
                        + (modern ? (caseSensitise ? name : lowerName) : (caseSensitise ? lowerName : name));
            }
            else if (module != null)
                internalName = "\0" + module.getInternalName() + "##" + (caseSensitise ? lowerName : name);
        }
    }

    public void setClazz(ClassEntity clazz) {
        this.clazz = clazz;
        this.module = clazz == null ? null : clazz.getModule();
        updateInternalName();
    }

    public ModuleEntity getModule() {
        return module;
    }

    public void setModule(ModuleEntity module) {
        this.module = module;
        updateInternalName();
    }

    public Memory getValue() {
        return value;
    }

    public Memory getValue(Environment env){
        return getValue(env, env.trace());
    }

    public Memory getValue(Environment env, php.runtime.env.TraceInfo callTrace) {
        if (clazz != null && clazz.getNativeClass() == php.runtime.ext.core.reflection.ReflectionProperty.class) {
            Memory profileValue = php.runtime.ext.core.reflection.PhpReflectionModifiers.constantValue(this,
                    php.runtime.ext.core.reflection.PhpReflectionModifiers.modern(env, callTrace));
            if (profileValue != null) return profileValue;
        }
        if (value == null && initializerMethod != null)
            return php.runtime.PhpConstantInitializer.resolve(this, env);
        if (value == null)
            return env.getStatic(internalName).toValue();
        else
            return value;
    }

    public void setValue(Memory value) {
        synchronized (this){
            this.value = value;
        }
    }

    public boolean isCaseSensitise() {
        return caseSensitise;
    }

    public boolean isOwned(ClassEntity entity){
        return clazz != null && clazz.getId() == entity.getId();
    }

    public Modifier getModifier() {
        return modifier;
    }

    public void setModifier(Modifier modifier) {
        this.modifier = modifier;
    }

    public boolean isPublic() {
        return modifier == Modifier.PUBLIC;
    }

    public boolean isProtected() {
        return modifier == Modifier.PROTECTED;
    }

    public boolean isPrivate() {
        return modifier == Modifier.PRIVATE;
    }

    public int canAccess(Environment env) {
        return canAccess(env, null, false);
    }

    public int canAccess(Environment env, ClassEntity context) {
        return canAccess(env, context, false);
    }

    /**
     * 0 - success
     * 1 - invalid protected
     * 2 - invalid private
     */
    public int canAccess(Environment env, ClassEntity context, boolean lateStaticCall) {
        return ObjectInvokeHelper.canAccess(env, modifier, clazz, context, lateStaticCall);
    }
}
