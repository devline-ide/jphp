package php.runtime.reflection.support;
import php.runtime.Memory;
import php.runtime.env.*;
import php.runtime.lang.Closure;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import php.runtime.common.PhpLanguageProfile;

/** Redaction copies only trace presentation values; callable arguments remain unchanged. */
public final class PhpSensitiveParameterTrace {
    private PhpSensitiveParameterTrace() {}
    private static AbstractFunctionEntity target(Environment env,CallStackItem item) {
        if(item.object instanceof php.runtime.invoke.PhpCallableFactory.CapturedClosure captured)return captured.getTarget();
        if(item.object instanceof Closure closure)return closure.getReflection().methodMagicInvoke;
        if(item.clazz!=null) {
            ClassEntity owner=item.object==null ? null : item.object.getReflection();
            if(owner==null)for(ClassEntity candidate:env.getClasses())if(candidate.getName().equalsIgnoreCase(item.clazz)){owner=candidate;break;}
            if(owner==null)owner=env.getScope().getClassMap().get(php.runtime.common.PhpClassNames.canonicalKey(item.clazz));
            return owner==null || item.function==null ? null : owner.findMethod(item.function.toLowerCase(java.util.Locale.ROOT));
        }
        if(item.function==null)return null;
        for(FunctionEntity function:env.getFunctions())if(function.getName().equalsIgnoreCase(item.function))return function;
        return env.getScope().getFunctionMap().get(item.function.toLowerCase(java.util.Locale.ROOT));
    }
    public static Memory[] redact(Environment env,CallStackItem item,Memory[] copy) {
        if(copy==null || copy.length==0)return copy;
        AbstractFunctionEntity target=target(env,item);
        ModuleEntity module=target instanceof FunctionEntity function ? function.getModule()
                : target instanceof MethodEntity method && method.getClazz()!=null ? method.getClazz().getModule() : null;
        if(module==null || module.getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5 || target.getParameters()==null)return copy;
        ParameterEntity[] parameters=target.getParameters();
        for(int i=0;i<copy.length;i++) {
            int position=i<parameters.length ? i : parameters.length-1;
            if(position<0 || i>=parameters.length && !parameters[position].isVariadic())continue;
            if(parameters[position].getPhpAttributes().stream().anyMatch(a->a.name().equalsIgnoreCase("SensitiveParameter")))
                copy[i]=ObjectMemory.valueOf(new php.runtime.ext.core.classes.PhpSensitiveParameterValue(env,copy[i]));
        }
        return copy;
    }
}
