package php.runtime.ext.core;

import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.lang.Resource;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;

/** Modern language helpers, published through the profile-isolated native provider map. */
public final class Php85LangFunctions extends FunctionsContainer {
    @Signature(result=@Arg(type=HintType.CALLABLE,nullable=true))
    public static Memory get_error_handler(Environment env) {
        return php.runtime.PhpOwnershipNativeCalls.currentErrorHandlerResult(env,env.getErrorHandler());
    }
    @Signature(result=@Arg(type=HintType.CALLABLE,nullable=true))
    public static Memory get_exception_handler(Environment env) {
        return php.runtime.PhpOwnershipNativeCalls.currentExceptionHandlerResult(env,env.getExceptionHandler());
    }
    @Signature(value={@Arg("value")},result=@Arg(type=HintType.STRING))
    public static String get_debug_type(Memory input) {
        Memory value=input.toValue();
        if(value.isObject()) {
            var object=value.toValue(ObjectMemory.class).value;
            if(object instanceof Resource resource)return "resource ("+resource.getResourceType()+")";
            ClassEntity clazz=object.getReflection();
            if(!clazz.isAnonymous())return clazz.getName();
            String name=clazz.getParent()!=null?clazz.getParent().getName():
                    clazz.getInterfaces().isEmpty()?"class":clazz.getInterfaces().values().iterator().next().getName();
            return name+"@anonymous";
        }
        return switch(value.getRealType()) {
            case BOOL -> "bool";
            case INT -> "int";
            case DOUBLE -> "float";
            case STRING -> "string";
            case ARRAY -> "array";
            default -> "null";
        };
    }
    @Signature(value={@Arg(value="object",type=HintType.OBJECT)},result=@Arg(type=HintType.ARRAY))
    public static Memory get_mangled_object_vars(Environment env,TraceInfo trace,Memory object) {
        Memory value=object.toValue();
        if(!value.isObject() || value.toValue(ObjectMemory.class).value instanceof Resource) {
            env.exception(trace,php.runtime.lang.exception.BaseTypeError.class,
                    "get_mangled_object_vars(): Argument #1 ($object) must be of type object, %s given",
                    value.isObject()?"resource":value.getRealType()==Memory.Type.BOOL?(value.toBoolean()?"true":"false"):get_debug_type(value));
            return Memory.NULL;
        }
        return value.toArray();
    }
    @Signature(value={@Arg(value="class",type=HintType.STRING),@Arg(value="alias",type=HintType.STRING),
            @Arg(value="autoload",type=HintType.BOOLEAN,optional=@Optional("true"))},result=@Arg(type=HintType.BOOLEAN))
    public static boolean class_alias(Environment env,TraceInfo trace,String original,String alias,boolean autoload) {
        if(original.startsWith("\\"))original=original.substring(1);
        if(alias.startsWith("\\"))alias=alias.substring(1);
        ClassEntity clazz=env.fetchClass(original,autoload,PhpLanguageProfile.PHP_8_5);
        if(clazz==null) {
            env.error(trace,php.runtime.exceptions.support.ErrorType.E_WARNING,"Class \"%s\" not found",original);
            return false;
        }
        if(!env.registerClassAlias(alias,clazz,PhpLanguageProfile.PHP_8_5)) {
            env.error(trace,php.runtime.exceptions.support.ErrorType.E_WARNING,"Cannot declare %s %s, because the name is already in use",
                    clazz.isInterface()?"interface":clazz.isTrait()?"trait":clazz.isEnum()?"enum":"class",alias);
            return false;
        }
        return true;
    }
    @Signature(value={@Arg(value="class",type=HintType.STRING),@Arg(value="alias",type=HintType.STRING),
            @Arg(value="autoload",type=HintType.BOOLEAN,optional=@Optional("true"))},result=@Arg(type=HintType.BOOLEAN))
    public static boolean class_alias(Environment env,TraceInfo trace,String original,String alias) {
        return class_alias(env,trace,original,alias,true);
    }
}
