package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.*;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseError;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.ParameterEntity;
import php.runtime.reflection.support.PhpPropertyHooks;

/** Explicit parent operations use the original receiver and a normal isolated SEND frame. */
public final class PhpPropertyHookParentCall {
    private PhpPropertyHookParentCall() {}
    public static PhpCallFrame frame(PhpPropertyHooks.Hook body,IObject receiver,Environment env,TraceInfo trace,
                                    String name,int kind,int capacity) {
        if(body==null || receiver==null || kind<1 || kind>2 || capacity<0
                || !body.declaration().getName().equals(name)
                || (body.kind()==PhpPropertyHooks.Kind.GET?1:2)!=kind
                || !PhpPropertyHookBodyAccess.isBackingAccess(body,receiver,env,receiver,body.declaration()))
            throw new IllegalArgumentException("Parent hook call lacks its actual executing body/receiver/property/kind");
        if(PhpRuntimeOwnership.current(env)!=null || PhpOwnershipExecution.current(env)!=null)
            throw new IllegalArgumentException("Parent hook call needs its complete actual ownership protocol");
        ClassEntity scope=body.method().getClazz(),parent=scope.getParent();
        if(parent==null) {
            env.exception(trace,BaseError.class,"Cannot access parent when current class scope has no parent");
            throw new IllegalStateException("Missing parent exception returned");
        }
        PropertyEntity property=parent.findProperty(name);
        if(property==null) {
            env.exception(trace,BaseError.class,"Undefined property %s::$%s",parent.getName(),name);
            throw new IllegalStateException("Undefined parent property exception returned");
        }
        if(property.canAccess(env,scope)!=0) {
            env.exception(trace,BaseError.class,"Cannot access private property %s::$%s",property.getClazz().getName(),name);
            throw new IllegalStateException("Inaccessible parent property exception returned");
        }
        var hooks=property.getPhpHooks();var hook=hooks==null?null:kind==1?hooks.get():hooks.set();
        if(hook!=null) {
            hook.validate();
            if(hook.method().isAbstractable()) {
                env.exception(trace,BaseError.class,"Cannot call abstract method %s::$%s::%s()",hook.method().getClazzName(),name,kind==1?"get":"set");
                throw new IllegalStateException("Abstract parent hook exception returned");
            }
            var actual=hook.method().getNativeMethod();
            if(actual==null || actual.getDeclaringClass()!=hook.method().getClazz().getNativeClass()
                    || !actual.getName().equals(hook.method().getInternalName())
                    || !actual.getDeclaringClass().isInstance(receiver))
                throw new IllegalArgumentException("Parent hook lost its exact declaring/private native member");
            return PhpCallFrame.resolvedInvoker(env,trace,new DynamicMethodInvoker(env,trace,receiver,hook.method()),capacity);
        }
        // PHP permits invoking the parent's ordinary backing operation when that hook is absent.
        return PhpCallFrame.resolvedInvoker(env,trace,new BackingInvoker(env,trace,receiver,parent,
                receiver.getReflection().findProperty(name),kind),capacity);
    }
    /** Allocated only for an explicit missing parent hook; no fields are added to ordinary call frames or objects. */
    public static final class BackingInvoker extends Invoker {
        private final IObject receiver;
        private final ClassEntity parent;
        private final PropertyEntity property;
        private final int kind;
        private final ParameterEntity[] parameters;
        private BackingInvoker(Environment env,TraceInfo trace,IObject receiver,ClassEntity parent,PropertyEntity property,int kind) {
            super(env,trace);this.receiver=receiver;this.parent=parent;this.property=property;this.kind=kind;
            if(property==null)throw new IllegalArgumentException("Parent backing operation lost its effective property");
            if(kind==1)parameters=new ParameterEntity[0];
            else {
                var value=new ParameterEntity(property.getContext());value.setName("value");value.setTrace(property.getTrace());
                // The implicit operation validates the backing property, not a declared hook parameter.
                parameters=new ParameterEntity[]{value};
            }
        }
        public PhpArgumentBinder.Signature signature(){return new PhpArgumentBinder.Signature(this,parameters,true,false);}
        @Override public ParameterEntity[] getParameters(){return parameters;}
        @Override public int getArgumentCount(){return parameters.length;}
        @Override public String getName(){return parent.getName()+"::$"+property.getName()+"::"+(kind==1?"get":"set");}
        @Override public int canAccess(Environment env){return 0;}
        @Override protected void pushCall(TraceInfo trace,Memory[] args) {
            env.pushCall(trace,receiver,args,"$"+property.getName()+"::"+(kind==1?"get":"set"),parent.getName(),receiver.getReflection().getName());
        }
        @Override protected Memory invoke(Memory... args)throws Throwable {
            pushCall(trace,args);
            try {
                if(property.getPhpHooks()!=null && property.getPhpHooks().isVirtual())
                    PhpPropertyHookAccess.error(env,trace,kind==1?"Must not read from virtual property %s::$%s":"Must not write to virtual property %s::$%s",property);
                Memory[] passed=InvokeArgumentHelper.makeArguments(env,args,parameters,getName(),null,parent.getName(),trace,false);
                if(kind==1)return property.readInitialized(env,trace,receiver.getProperties().getByScalar(property.getSpecificName())).toImmutable();
                // The native implicit parent setter uses weak backing coercion even in a strict caller.
                var write=PhpPropertyWriteContext.enter(env,trace,false);
                try{property.assignValue(env,trace,receiver,property.getSpecificName(),passed[0]);return Memory.NULL;}
                finally{PhpPropertyWriteContext.leave(write);}
            }finally{env.popCall();}
        }
    }
}
