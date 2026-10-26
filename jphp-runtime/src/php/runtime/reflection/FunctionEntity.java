package php.runtime.reflection;

import php.runtime.Memory;
import php.runtime.common.Function;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.InvokeHelper;
import php.runtime.lang.Closure;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.support.AbstractFunctionEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class FunctionEntity extends AbstractFunctionEntity {
    protected boolean isInternal = false;
    protected ModuleEntity module;

    private Class<?> nativeClazz;
    private Method nativeMethod;

    private boolean isStatic = false;

    public FunctionEntity(Context context) {
        super(context);
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public boolean isInternal() {
        return isInternal;
    }

    public Class<?> getNativeClazz() {
        return nativeClazz;
    }

    public void setNativeClazz(Class<?> nativeClazz) {
        this.nativeClazz = nativeClazz;
    }

    public Method getNativeMethod() {
        return nativeMethod;
    }

    public void setNativeMethod(Method nativeMethod) {
        this.nativeMethod = nativeMethod;
        nativeMethod.setAccessible(true);
    }

    public boolean isDeprecated(){
        return php.runtime.invoke.PhpDeprecatedInvocation.marker(this) != null;
    }

    public ModuleEntity getModule() {
        return module;
    }

    public void setModule(ModuleEntity module) {
        this.module = module;
    }

    public boolean hasParameters() {
        return parameters != null && parameters.length > 0;
    }

    public Memory invoke(Environment env, TraceInfo trace, Memory[] arguments) throws Throwable {
        php.runtime.PhpOwnershipExecution.requireOwnershipExecution(env,module);
        if (php.runtime.PhpOwnershipExecution.current(env) != null)
            return php.runtime.PhpOwnershipExecution.invokeFunction(this,env,trace,arguments);
        if (php.runtime.PhpRuntimeOwnership.isInvoking(env,this))
            return php.runtime.PhpRuntimeOwnership.invokeFunctionBody(this,env,trace,arguments);
        try {
            return InvokeHelper.checkReturnType(
                    env, trace,
                    (Memory)nativeMethod.invoke(null, env, arguments),
                    this
            );
        } catch (InvocationTargetException e){
            return env.__throwException(e);
        } finally {
            unsetArguments(arguments);
        }
    }

    @Override
    public Memory getImmutableResultTyped(Environment env, TraceInfo trace) {
        php.runtime.PhpOwnershipExecution.requireOwnershipExecution(env,module);
        if (php.runtime.invoke.PhpDeprecatedInvocation.marker(this) != null) return null;
        if (php.runtime.PhpOwnershipExecution.current(env) != null || php.runtime.PhpRuntimeOwnership.isInvoking(env,this)) return null;
        Memory result = getImmutableResultForTypeCheck(module);
        if (result != null && module != null && module.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            return InvokeHelper.checkReturnType(env, trace, result, this);

        if (result != null && !resultTypeChecked) {
            result = InvokeHelper.checkReturnType(env, trace, result, this);

            if (result != null) {
                this.result = result;
                this.resultTypeChecked = true;
            }
        }

        return result;
    }

    @Override
    public Closure getClosure(Environment env) {
        if (php.runtime.invoke.PhpDeprecatedInvocation.marker(this) != null)
            return (Closure) php.runtime.invoke.PhpCallableFactory.fromResolvedFunction(this, env, env.trace())
                    .toValue(ObjectMemory.class).value;
        final FunctionEntity bind = this;
        final ClosureEntity closureEntity1 = new ClosureEntity(this.getContext());
        closureEntity1.setParent(env.scope.fetchUserClass(Closure.class));
        closureEntity1.setModule(module);
        closureEntity1.parameters = this.parameters;
        closureEntity1.setReturnReference(this.isReturnReference());
        closureEntity1.setGeneratorEntity(getGeneratorEntity());

        MethodEntity m = new MethodEntity(this);
        m.setClazz(closureEntity1);
        m.setName("__invoke");
        closureEntity1.addMethod(m, null);
        closureEntity1.doneDeclare();

        Closure tmp = new Closure(env, closureEntity1, Memory.NULL, null, new Memory[0]){
            @Override
            public Memory __invoke(Environment e, Memory... args) {
                try {
                    return bind.invoke(e, e.peekCall(0).trace, args);
                } catch (RuntimeException e1){
                    throw e1;
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            }

            @Override
            public Memory getOrCreateStatic(String name) {
                return Memory.NULL;
            }

            @Override
            public ClassEntity getReflection() {
                return closureEntity1;
            }
        };

        try {
            m.setNativeMethod(tmp.getClass().getDeclaredMethod("__invoke", Environment.class, Memory[].class));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        tmp.initializeReflectionBinding(null, module != null
                ? module.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                : env.getModuleManager().getPhpLanguageProfile(env.trace()) == php.runtime.common.PhpLanguageProfile.PHP_8_5);
        return tmp;
    }
}
