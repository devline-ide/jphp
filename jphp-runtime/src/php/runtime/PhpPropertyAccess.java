package php.runtime;

import php.runtime.common.Modifier;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseError;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;

/** Permission concerns the exact property operation/address acquisition, never later alias assignments. */
public final class PhpPropertyAccess {
    private PhpPropertyAccess() {}
    public enum Intent { ASSIGN, MUTABLE_BACKING_ADDRESS, UNSET }
    public static int rank(Modifier modifier){return modifier==Modifier.PUBLIC?0:modifier==Modifier.PROTECTED?1:2;}
    public static void validate(PropertyEntity property,Environment env) {
        if(property.getPhpSetModifier()==null)return;
        if(property.getDeclaredType()==null)env.error(property.getTrace(),php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR,
                "Property with asymmetric visibility %s::$%s must have type",property.getClazz().getName(),property.getName());
        if(rank(property.effectiveSetModifier())<rank(property.getModifier()))env.error(property.getTrace(),php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR,
                "Visibility of property %s::$%s must not be weaker than set visibility",property.getClazz().getName(),property.getName());
        var hooks=property.getPhpHooks();
        if(hooks!=null && hooks.isVirtual() && hooks.get()!=null && hooks.set()==null)
            env.error(property.getTrace(),php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR,
                    "Read-only virtual property %s::$%s must not specify asymmetric visibility",property.getClazz().getName(),property.getName());
    }
    public static boolean allowed(PropertyEntity property,ClassEntity scope) {
        Modifier writer=property.effectiveSetModifier();ClassEntity declaration=property.getClazz();
        return writer==Modifier.PUBLIC || scope!=null && (writer==Modifier.PRIVATE?scope==declaration:
                scope.isInstanceOf(declaration)||declaration.isInstanceOf(scope));
    }
    public static void check(PropertyEntity property,Environment env,TraceInfo trace,Intent intent) {
        if(property==null || property.getPhpSetModifier()==null || property.effectiveSetModifier()==property.getModifier())return;
        ClassEntity scope=env.getLastClassOnStack();if(allowed(property,scope))return;
        String operation=intent==Intent.UNSET?"unset":intent==Intent.MUTABLE_BACKING_ADDRESS?"indirectly modify":"modify";
        env.exception(trace,BaseError.class,"Cannot %s %s(set) property %s::$%s from %s",operation,
                property.effectiveSetModifier()==Modifier.PRIVATE?"private":"protected",property.getClazz().getName(),property.getName(),
                scope==null?"global scope":"scope "+scope.getName());
    }
}
