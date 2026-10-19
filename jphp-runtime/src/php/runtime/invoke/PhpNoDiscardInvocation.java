package php.runtime.invoke;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.core.LangFunctions;
import php.runtime.ext.core.reflection.ReflectionAttribute;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.support.AbstractFunctionEntity;
import php.runtime.reflection.support.PhpAttribute;
/** Explicit discarded-result routes: no additional frame fields, ThreadLocal or ordinary-call allocation. */
public final class PhpNoDiscardInvocation {
    private PhpNoDiscardInvocation(){}
    public static PhpAttribute marker(AbstractFunctionEntity target){
        if(target!=null){var attrs=target.getPhpAttributes();for(int i=0;i<attrs.size();i++)
            if(attrs.get(i).name().equalsIgnoreCase("NoDiscard"))return attrs.get(i);}
        return null;
    }
    public static java.lang.reflect.Method nativeDiscardedMember(java.lang.reflect.Method member){
        return PhpNativeResultUse.select(member,true);
    }
    public static void beforeBody(Environment env,TraceInfo trace,AbstractFunctionEntity target) throws Throwable {
        var attribute=marker(target);if(attribute==null || env.getModuleManager().getPhpLanguageProfile(trace)!=PhpLanguageProfile.PHP_8_5)return;
        // Attribute message construction and warning handlers are opaque to primitive CELL custody.
        php.runtime.PhpPrimitiveCellRuntime.opaque(env);
        Memory message=Memory.NULL;
        if(!attribute.arguments().isEmpty()){
            if(php.runtime.PhpOwnershipExecution.current(env)!=null||php.runtime.PhpRuntimeOwnership.current(env)!=null)
                message=PhpNoDiscardOwnedInvocation.message(env,trace,target,attribute);
            else {
                var value=new ReflectionAttribute(env,target,attribute).instantiateForDiscardedCall(env,trace);
                message=value.toValue(ObjectMemory.class).value.getProperties().valueOfIndex("message").toValue();
            }
        }
        boolean closure=target instanceof php.runtime.reflection.MethodEntity method
                && method.getClazz() instanceof php.runtime.reflection.helper.ClosureEntity;
        boolean function=closure || attribute.target()==PhpAttribute.FUNCTION;
        String name=closure ? "{closure:"+target.getTrace().getFileName()+":"+(target.getTrace().getStartLine()+1)+"}"
                :function?target.getName():target instanceof php.runtime.reflection.MethodEntity method
                ?method.getClazzName()+"::"+method.getName():target.getName();
        String display=(function?"function":"method")+" "+name;
        if(closure){
            var method=(php.runtime.reflection.MethodEntity)target;
            Class<?> physical=env.getScope().getClassLoader().loadClass(method.getClazz().getCompiledInternalName());
            try{display=(String)physical.getDeclaredField("__$noDiscardClosureName").get(null);}
            catch(NoSuchFieldException absent){/* Older declarations retain their original file diagnostic. */}
        }
        String warning="The return value of "+display
                +"() should either be used or intentionally ignored by casting it as (void)";
        if(!message.isNull()&&!message.toString().isEmpty())warning+=", "+message;
        env.error(trace,ErrorType.E_USER_WARNING,warning);
    }
    public static Memory invokeDiscarded(Environment env,TraceInfo trace,Invoker invoker,Memory[] passed,boolean internal) throws Throwable {
        PhpCallFrame.beforeRetainedDiscardedInvocation(invoker);
        if(invoker instanceof DynamicMethodInvoker dynamic){
            if(dynamic.getObject() instanceof PhpCallableFactory.CapturedClosure captured)
                return captured.invokePrepared(env,trace,passed,internal,true);
            if(dynamic instanceof MagicDynamicMethodInvoker magic)
                passed=new Memory[]{magic.methodName,PhpNamedArgumentTarget.magicArguments(env,passed,magic)};
            return ObjectInvokeHelper.invokeMethod(dynamic.getObject(),dynamic.getMethod(),env,trace,passed,false,internal,true);
        }
        if(invoker instanceof StaticMethodInvoker statik){
            if(statik instanceof MagicStaticMethodInvoker magic)
                passed=new Memory[]{magic.methodName,PhpNamedArgumentTarget.magicArguments(env,passed,magic)};
            return InvokeHelper.callStatic(env,trace,statik.getMethod(),statik.getCalledClass(),passed,false,internal,true);
        }
        if(invoker instanceof FunctionInvoker function)return InvokeHelper.call(env,trace,function.getFunction(),passed,internal,true);
        return invoker==null?Memory.NULL:invoker.call(passed);
    }
}
