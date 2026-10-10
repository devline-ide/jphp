package php.runtime.ext.core.reflection;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.ext.support.Extension;
import php.runtime.lang.Closure;
import php.runtime.lang.IObject;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.CompileFunctionEntity;
import php.runtime.reflection.ParameterEntity;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.support.AbstractFunctionEntity;
import php.runtime.reflection.support.TypeChecker;

import static php.runtime.annotation.Reflection.Signature;

abstract public class ReflectionFunctionAbstract extends Reflection {

    public ReflectionFunctionAbstract(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }

    public ReflectionFunctionAbstract(Environment env) {
        super(env);
    }

    protected IObject getInstance() { return null; }
    abstract protected AbstractFunctionEntity getEntity();
    protected ClosureEntity getClosureEntity() { return null; }
    private php.runtime.invoke.PhpCallableFactory.CapturedClosure captured() {
        return getInstance() instanceof php.runtime.invoke.PhpCallableFactory.CapturedClosure
                ? (php.runtime.invoke.PhpCallableFactory.CapturedClosure) getInstance() : null;
    }

    @Signature
    public Memory getClosureScopeClass(Environment env, Memory... args){
        IObject instance = getInstance();
        if (instance == null || !(instance instanceof Closure))
            return Memory.NULL;
        else {
            Closure closure = (Closure) instance;
            if (closure.hasPhp8Binding()) {
                ClassEntity scope = closure.getScope() == null ? null : env.fetchClass(closure.getScope(), false);
                return scope == null ? Memory.NULL : new ObjectMemory(new ReflectionClass(env).setEntity(scope));
            }
            // todo: change to ReflectionClass
            return new StringMemory(((Closure) instance).getScope());
        }
    }

    @Signature
    public Memory getClosureCalledClass(Environment env, Memory... args) {
        IObject instance = getInstance();
        if (!(instance instanceof Closure)) return Memory.NULL;
        String name = ((Closure) instance).getCalledClass();
        ClassEntity called = name == null ? null : env.fetchClass(name, false);
        return called == null ? Memory.NULL : new ObjectMemory(new ReflectionClass(env).setEntity(called));
    }

    @Signature
    public Memory getClosureThis(Environment env, Memory... args){
        IObject instance = getInstance();
        if (instance == null || !(instance instanceof Closure))
            return Memory.NULL;
        else {
            return ((Closure) instance).getSelf();
        }
    }

    @Signature
    public Memory getDocComment(Environment env, Memory... args){
        if (captured() != null) return getEntity().getDocComment() == null ? Memory.FALSE : new StringMemory(getEntity().getDocComment().toString());
        if (getClosureEntity() != null)
            return Memory.FALSE;
        if (getEntity().getDocComment() == null)
            return Memory.FALSE;

        return new StringMemory(getEntity().getDocComment().toString());
    }

    @Signature
    public Memory getEndLine(Environment env, Memory... args){
        if (captured() != null) return captured().isInternalCapture() ? Memory.FALSE : LongMemory.valueOf(getEntity().getTrace().getEndLine());
        if (getClosureEntity() != null)
            return LongMemory.valueOf(getClosureEntity().getTrace().getEndLine());
        return LongMemory.valueOf(getEntity().getTrace().getEndLine());
    }

    @Signature
    public Memory getExtension(Environment env, Memory... args){
        if (getClosureEntity() != null && captured() == null)
            return Memory.NULL;

        Extension extension = getEntity().getExtension();
        if (extension == null)
            return Memory.NULL;
        else {
            ReflectionExtension ext = new ReflectionExtension(env, env.fetchClass("ReflectionExtension"));
            ext.setExtension(extension);
            return new ObjectMemory(ext);
        }
    }

    @Signature
    public Memory getExtensionName(Environment env, Memory... args){
        if (getClosureEntity() != null && captured() == null)
            return Memory.FALSE;

        Extension extension = getEntity().getExtension();
        if (extension == null)
            return Memory.FALSE;
        else
            return new StringMemory(extension.getName());
    }

    @Signature
    public Memory getFileName(Environment env, Memory... args){
        if (captured() != null) return captured().isInternalCapture() ? Memory.FALSE : new StringMemory(getEntity().getTrace().getFileName());
        if (getClosureEntity() != null)
            return new StringMemory(getClosureEntity().getTrace().getFileName());
        return new StringMemory(getEntity().getTrace().getFileName());
    }

    @Signature
    public Memory getName(Environment env, Memory... args){
        if (captured() != null) return new StringMemory(captured().getMetadataName());
        if (getClosureEntity() != null)
            return Memory.FALSE;
        return new StringMemory(getEntity().getName());
    }

    @Signature
    public Memory getNamespaceName(Environment env, Memory... args){
        if (captured() != null) return new StringMemory(captured().isMagicCapture() ? "" : getEntity().getNamespaceName());
        if (getClosureEntity() != null)
            return Memory.FALSE;
        return new StringMemory(getEntity().getNamespaceName());
    }

    @Signature
    public Memory getNumberOfParameters(Environment env, Memory... args){
        if (getClosureEntity() != null)
            return LongMemory.valueOf(getClosureEntity().parameters.length);
        return LongMemory.valueOf(getEntity().getParameters().length);
    }

    @Signature
    public Memory getNumberOfRequiredParameters(Environment env, Memory... args){
        int cnt = 0;
        ParameterEntity[] parameterEntities = getClosureEntity() == null
                ? getEntity().getParameters() : getClosureEntity().parameters;

        boolean modern = env.getModuleManager().getPhpLanguageProfile(env.trace()) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        for (int i=0; i<parameterEntities.length; i++) {
            ParameterEntity parameter = parameterEntities[i];
            if (modern) {
                if (!parameter.isVariadic() && parameter.getDefaultValue() == null) cnt = i + 1;
            } else if (parameter.getDefaultValue() == null) cnt++;
        }
        return LongMemory.valueOf(cnt);
    }

    @Signature
    public Memory getShortName(Environment env, Memory... args){
        if (captured() != null) return new StringMemory(captured().isMagicCapture() ? captured().getMetadataName() : getEntity().getShortName());
        if (getClosureEntity() != null)
            return Memory.NULL;
        return new StringMemory(getEntity().getShortName());
    }

    @Signature
    public Memory getStartLine(Environment env, Memory... args){
        if (captured() != null) return captured().isInternalCapture() ? Memory.FALSE : LongMemory.valueOf(getEntity().getTrace().getStartLine() + 1);
        if (getClosureEntity() != null)
            return LongMemory.valueOf(getClosureEntity().getTrace().getStartLine() + 1);
        return LongMemory.valueOf(getEntity().getTrace().getStartLine() + 1);
    }

    @Signature
    public Memory getPosition(Environment env, Memory... args){
        if (getClosureEntity() != null)
            return LongMemory.valueOf(getClosureEntity().getTrace().getStartPosition() + 1);
        return LongMemory.valueOf(getEntity().getTrace().getStartPosition() + 1);
    }

    @Signature
    public Memory getStaticVariables(Environment env, Memory... args) {
        return Memory.FALSE;
    }

    @Signature
    public Memory inNamespace(Environment env, Memory... args){
        if (captured() != null) return !captured().isMagicCapture() && !getEntity().getNamespaceName().isEmpty() ? Memory.TRUE : Memory.FALSE;
        if (getClosureEntity() != null)
            return Memory.FALSE;
        return getEntity().getNamespaceName().isEmpty() ? Memory.FALSE : Memory.TRUE;
    }

    @Signature
    public Memory isClosure(Environment env, Memory... args){
        return getClosureEntity() != null ? Memory.TRUE : Memory.FALSE;
    }

    @Signature
    public Memory isDeprecated(Environment env, Memory... args){
        if (captured() != null) return getEntity().isDeprecated() ? Memory.TRUE : Memory.FALSE;
        if (getClosureEntity() != null)
            return Memory.FALSE;
        return getEntity().isDeprecated() ? Memory.TRUE : Memory.FALSE;
    }

    @Signature
    public Memory isInternal(Environment env, Memory... args){
        return (captured() != null ? captured().isInternalCapture() : getEntity() instanceof CompileFunctionEntity) ? Memory.TRUE : Memory.FALSE;
    }

    @Signature
    public Memory isUserDefined(Environment env, Memory... args){
        return (captured() != null ? captured().isInternalCapture() : getEntity() instanceof CompileFunctionEntity) ? Memory.FALSE : Memory.TRUE;
    }

    @Signature
    public Memory returnsReference(Environment env, Memory... args) {
        if (getClosureEntity() != null)
            return getClosureEntity().isReturnReference() ? Memory.TRUE : Memory.FALSE;
        return getEntity().isReturnReference() ? Memory.TRUE : Memory.FALSE;
    }

    @Signature
    public Memory hasReturnType(Environment env, Memory... args) {
        if (captured() != null && captured().isMagicCapture()) return Memory.FALSE;
        return getEntity().getReturnTypeChecker() != null ? Memory.TRUE : Memory.FALSE;
    }

    @Signature
    public Memory getReturnType(Environment env, Memory... args) {
        if (captured() != null && captured().isMagicCapture()) return Memory.NULL;
        TypeChecker typeChecker = getEntity().getReturnTypeChecker();

        if (typeChecker == null) {
            return Memory.NULL;
        }

        if (getEntity() instanceof php.runtime.reflection.FunctionEntity
                && ((php.runtime.reflection.FunctionEntity) getEntity()).getModule() != null
                && ((php.runtime.reflection.FunctionEntity) getEntity()).getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                || getEntity() instanceof php.runtime.reflection.MethodEntity
                && ((php.runtime.reflection.MethodEntity) getEntity()).getClazz().getModule() != null
                && ((php.runtime.reflection.MethodEntity) getEntity()).getClazz().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            return ObjectMemory.valueOf(ReflectionType.fromDeclaration(env,
                    php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(typeChecker, getEntity().isReturnTypeNullable())));
        }

        String name = typeChecker.getSignature();
        boolean isBuiltin = typeChecker.isBuiltin();

        return ObjectMemory.valueOf(new ReflectionType(env, name, getEntity().isReturnTypeNullable(), isBuiltin));
    }

    @Signature
    abstract public Memory getParameters(Environment env, Memory... args);
}
