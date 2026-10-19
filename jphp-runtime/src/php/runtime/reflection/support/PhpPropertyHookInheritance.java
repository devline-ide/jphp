package php.runtime.reflection.support;

import java.util.ArrayList;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;

/** Cold effective hook composition; inherited bodies retain their exact declaration and JVM member. */
public final class PhpPropertyHookInheritance {
    private PhpPropertyHookInheritance() {}
    public static void prepare(ClassEntity owner,Environment env) {
        for(PropertyEntity property:owner.getProperties()) {
            if(property.getClazz()!=owner) continue;
            PropertyEntity parent=owner.getParent()==null?null:owner.getParent().findProperty(property.getName());
            if(parent!=null && !parent.isPrivate() && parent!=property) merge(property,parent,owner,env);
            var hooks=property.getPhpHooks();
            if(hooks==null) continue;
            if(hooks.isVirtual() && property.isDefault())
                env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,
                        "Cannot specify default value for virtual hooked property %s::$%s",owner.getName(),property.getName());
            hooks.validateFor(property);
            if(hooks.set()!=null) {
                var parameter=hooks.set().method().getParameters()[0];
                var accepted=PhpTypeDeclaration.fromChecker(parameter.getTypeChecker(),parameter.isNullable());
                var type=property.getDeclaredType()==null?PhpTypeDeclaration.fromChecker(null,false):property.getDeclaredType();
                if(!type.isSubtypeOf(accepted,owner,hooks.set().method().getClazz(),owner.getModule(),env))
                    env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,
                            "Set type of %s::$%s must be a supertype of the property type",owner.getName(),property.getName());
            }
        }
        // The final cold obligation collector aggregates ordinary methods and effective hooks.
    }
    private static void merge(PropertyEntity property,PropertyEntity parent,ClassEntity owner,Environment env) {
        var own=property.getPhpHooks();var inherited=parent.getPhpHooks();
        if(own==null && inherited==null) return;
        property.setPrototype(parent);
        boolean virtual=own!=null && !own.declaredOrder().isEmpty() && own.isDeclaredVirtual() && inherited!=null && inherited.isVirtual();
        var result=new ArrayList<PhpPropertyHooks.Hook>();
        if(own!=null)result.addAll(own.declaredOrder());
        if(inherited!=null)for(var required:inherited.declarationOrder()) {
            var override=own==null?null:own.declared(required.kind());
            if(override!=null) {
                if(override.method()!=required.method()) {
                    if(required.method().isFinal())env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,
                            "Cannot override final property hook %s::$%s::%s()",required.declaration().getClazz().getName(),
                            parent.getName(),required.kind()==PhpPropertyHooks.Kind.GET?"get":"set");
                    override.method().setPrototype(required.method());
                    if(!PhpMethodCompatibility.compatible(override.method(),required.method(),owner.getModule(),env))
                        env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,"Declaration of %s must be compatible with %s",
                                override.method().getSignatureString(true),required.method().getSignatureString(true));
                }
            } else if(!required.method().isAbstractable() || virtual) result.add(required);
            // An ordinary backed read/write satisfies a missing abstract operation directly.
        }
        if(virtual && property.isDefault())
            env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,
                    "Cannot specify default value for virtual hooked property %s::$%s",owner.getName(),property.getName());
        if(!virtual) {
            PhpPropertyHooks.Hook get=null,set=null;
            for(var hook:result) {if(hook.kind()==PhpPropertyHooks.Kind.GET)get=hook;else set=hook;}
            if(get!=null && get.method().isReturnReference() && set!=null)
                env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,
                        "Get hook of backed property %s::%s with set hook may not return by reference",owner.getName(),property.getName());
        }
        if(!result.isEmpty())property.setPhpHooks(PhpPropertyHooks.inherited(parent,virtual,result,own));
    }
}
