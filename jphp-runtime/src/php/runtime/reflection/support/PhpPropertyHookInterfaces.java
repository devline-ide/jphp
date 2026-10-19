package php.runtime.reflection.support;

import java.util.*;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.MethodEntity;

/** Cold interface obligations use the property catalog; hidden hooks never enter the ordinary method map. */
public final class PhpPropertyHookInterfaces {
    private PhpPropertyHookInterfaces() {}

    public static void validate(ClassEntity owner,Environment env) {
        if(owner.getInterfaces().isEmpty()){validateCompletion(owner,Collections.emptyMap(),env);return;}
        var contracts=new ArrayList<ClassEntity>();
        Set<ClassEntity> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(ClassEntity contract:owner.getInterfaces().values())collect(contract,contracts,seen);
        var missing=new LinkedHashMap<String,PhpPropertyHooks.Hook>();
        var prototypes=new IdentityHashMap<MethodEntity,MethodEntity>();
        var properties=new IdentityHashMap<PropertyEntity,PropertyEntity>();
        for(ClassEntity contract:contracts)for(PropertyEntity required:contract.getProperties()) {
            if(required.getClazz()!=contract||required.getPhpHooks()==null)continue;
            required.getPhpHooks().validateFor(required);
            PropertyEntity actual=owner.findProperty(required.getName());
            if(actual==null)actual=owner.findStaticProperty(required.getName());
            if(actual==null) {
                for(var hook:required.getPhpHooks().reflectionOrder())requireMissing(missing,hook);
                continue;
            }
            if(required.isFinal() && actual!=required)env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,
                    "Cannot override final property %s::$%s",contract.getName(),required.getName());
            if(actual.isStatic())env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,
                    "Cannot redeclare non static %s::$%s as static %s::$%s",contract.getName(),required.getName(),actual.getClazz().getName(),actual.getName());
            if(!actual.isPublic())env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,
                    "Access level to %s::$%s must be public (as in class %s)",actual.getClazz().getName(),actual.getName(),contract.getName());
            var effective=actual.getPhpHooks();
            if(effective!=null)effective.validateFor(actual);
            for(var needed:required.getPhpHooks().reflectionOrder()) {
                var implemented=effective==null?null:needed.kind()==PhpPropertyHooks.Kind.GET?effective.get():effective.set();
                if(needed.kind()==PhpPropertyHooks.Kind.SET&&(actual.isPhpReadonly()
                        || php.runtime.PhpPropertyAccess.rank(actual.effectiveSetModifier())>php.runtime.PhpPropertyAccess.rank(required.effectiveSetModifier())))env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,
                        "Set access level of %s::$%s must be omitted (as in class %s)",actual.getClazz().getName(),actual.getName(),contract.getName());
                if(implemented!=null) {
                    implemented.validate();needed.validate();
                    if(!PhpMethodCompatibility.compatible(implemented.method(),needed.method(),owner.getModule(),env))
                        env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,"Declaration of %s must be compatible with %s",signature(implemented),signature(needed));
                    if(implemented.method().isAbstractable())requireMissing(missing,needed);
                    // PHP shares inherited hook metadata; the latest interface supplies its exact root prototype.
                    if(implemented.method()!=needed.method())prototypes.put(implemented.method(),rootPrototype(needed));
                }else if(effective!=null&&effective.isVirtual())requireMissing(missing,needed);
            }
            validateType(actual,required,owner,env);
            if(actual.getClazz()==owner&&actual.getPrototype()==null)properties.put(actual,required);
        }
        validateCompletion(owner,missing,env);
        prototypes.forEach(MethodEntity::setPrototype);properties.forEach(PropertyEntity::setPrototype);
    }

    /** One final collector sees existing ordinary methods, effective abstract hooks and absent interface operations. */
    private static void validateCompletion(ClassEntity owner,Map<String,PhpPropertyHooks.Hook> missing,Environment env) {
        if(owner.isAbstract()||owner.isInterface()||owner.isTrait())return;
        List<String> descriptions=null;
        for(var method:owner.getMethods().values())if(method.isAbstractable()) {
            if(descriptions==null)descriptions=new ArrayList<>();
            descriptions.add(method.getClazz().getName()+"::"+method.getName());
        }
        Map<String,PhpPropertyHooks.Hook> obligations=missing.isEmpty()?null:new LinkedHashMap<>(missing);
        for(PropertyEntity property:owner.getProperties()) {
            var hooks=property.getPhpHooks();if(hooks==null)continue;
            for(var hook:hooks.reflectionOrder())if(hook.method().isAbstractable()) {
                if(obligations==null)obligations=new LinkedHashMap<>();
                // An actual inherited/owned abstract operation supplies the native name ahead of its interface requirement.
                obligations.put(property.getName()+"::"+hook.kind(),hook);
            }
        }
        if(obligations!=null)for(var hook:obligations.values()) {
            if(descriptions==null)descriptions=new ArrayList<>();
            descriptions.add(hook.declaration().getClazz().getName()+"::$"+hook.declaration().getName()+"::"+(hook.kind()==PhpPropertyHooks.Kind.GET?"get":"set"));
        }
        if(descriptions==null)return;
        int count=descriptions.size();String plural=count==1?"":"s";
        env.error(owner.getTrace(),ErrorType.E_ERROR,
                owner.isEnum()?"Enum %s must implement %s abstract method%s (%5$s)":
                "Class %s contains %s abstract method%s and must therefore be declared abstract or implement the remaining method%s (%s)",
                owner.getName(),count,plural,plural,String.join(", ",descriptions));
    }

    private static MethodEntity rootPrototype(PhpPropertyHooks.Hook needed) {
        MethodEntity prototype=needed.method();Set<MethodEntity> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(true) {
            if(!seen.add(prototype)||!prototype.getClazz().isInterface()||PhpPropertyHooks.ownedMethod(prototype.getClazz(),prototype)==null)
                throw new IllegalArgumentException("Interface hook prototype lacks its exact owned declaration");
            if(prototype.getPrototype()==null)return prototype;prototype=prototype.getPrototype();
        }
    }

    private static void collect(ClassEntity contract,List<ClassEntity> result,Set<ClassEntity> seen) {
        if(!seen.add(contract))return;
        for(ClassEntity parent:contract.getInterfaces().values())collect(parent,result,seen);
        result.add(contract);
    }

    private static void requireMissing(Map<String,PhpPropertyHooks.Hook> missing,PhpPropertyHooks.Hook needed) {
        String key=needed.declaration().getName()+"::"+needed.kind();var previous=missing.get(key);
        if(previous==null||needed.declaration().getClazz().isInstanceOf(previous.declaration().getClazz()))missing.put(key,needed);
    }

    private static void validateType(PropertyEntity actual,PropertyEntity required,ClassEntity owner,Environment env) {
        var type=actual.getDeclaredType();var contract=required.getDeclaredType();
        if(contract==null) {
            if(type!=null)env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,
                    "Type of %s::$%s must be omitted to match the parent definition in class %s",actual.getClazz().getName(),actual.getName(),required.getClazz().getName());
            return;
        }
        var hooks=required.getPhpHooks();boolean reads=hooks.get()!=null,writes=hooks.set()!=null;
        boolean valid=type!=null&&(!reads||type.isSubtypeOf(contract,actual.getClazz(),required.getClazz(),owner.getModule(),env))
                &&(!writes||contract.isSubtypeOf(type,required.getClazz(),actual.getClazz(),owner.getModule(),env));
        if(!valid)env.error(actual.getTrace(),ErrorType.E_COMPILE_ERROR,
                "Type of %s::$%s must be %s%s (as in class %s)",actual.getClazz().getName(),actual.getName(),
                reads&&writes?"":reads?"subtype of ":"supertype of ",contract.getErrorSignature(),required.getClazz().getName());
    }

    private static String signature(PhpPropertyHooks.Hook hook) {
        String signature=hook.method().getSignatureString(true);
        signature=hook.declaration().getClazz().getName()+signature.substring(signature.indexOf("::"));
        return (hook.method().isReturnReference()?"& ":"")+signature;
    }
}
