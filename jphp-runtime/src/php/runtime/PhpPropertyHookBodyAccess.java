package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.CallStackItem;
import php.runtime.lang.IObject;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.support.PhpPropertyHooks;

/** Uses one immutable hook metadata record; no receiver or per-access token is stored in metadata. */
public final class PhpPropertyHookBodyAccess {
    private PhpPropertyHookBodyAccess() {}
    public static PhpPropertyHooks.Hook bindGeneratedMethod(IObject receiver,Environment env,String property,int kind,String jvmMember) {
        if(receiver==null || kind<1 || kind>2) throw new IllegalArgumentException("Invalid generated hook-body address");
        CallStackItem entry=env.peekCall(0);ClassEntity owner=env.getLastClassOnStack();
        if(entry==null || owner==null || entry.object!=receiver || !receiver.getReflection().isInstanceOf(owner))
            throw new IllegalArgumentException("Hook-body receiver has no exact active declaring entry");
        PropertyEntity declaration=owner.findProperty(property);
        var hooks=declaration==null?null:declaration.getPhpHooks();
        var hook=hooks==null?null:kind==1?hooks.get():hooks.set();
        if(hook==null || hook.method().getClazz()!=owner || !hook.method().getName().equals(entry.function)
                || !hook.method().getInternalName().equals(jvmMember)
                || hook.method().getNativeMethod()==null
                || !hook.method().getNativeMethod().getDeclaringClass().isInstance(receiver))
            throw new IllegalArgumentException("Hook-body address does not bind the actual declared hook member");
        hook.validate();return hook;
    }
    /** The entry is the actual generated _run frame, not the captured PHP call-stack label alone. */
    public static PhpPropertyHooks.Hook bindGeneratedGenerator(php.runtime.lang.Generator runner,Environment env,
            String property,int kind,String jvmMember) {
        if(runner==null || kind!=1 || !"_run".equals(jvmMember)
                || !(runner.getReflection() instanceof php.runtime.reflection.helper.GeneratorEntity generator))
            throw new IllegalArgumentException("Invalid resumed hook-body address");
        var frames=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(stack->stack.skip(1).limit(2).toList());
        var caller=frames.getFirst();
        if(frames.size()!=2 || !runner.__isHookDispatcher(frames.get(1))
                || caller.getDeclaringClass()!=generator.getNativeClass() || runner.getClass()!=generator.getNativeClass()
                || !caller.getMethodName().equals(jvmMember)
                || !caller.getMethodType().equals(java.lang.invoke.MethodType.methodType(Memory.class,Environment.class,Memory[].class)))
            throw new IllegalArgumentException("Resumed hook binding has no actual generated body entry");
        IObject receiver=runner.__getHookReceiver();
        CallStackItem entry=env.peekCall(0);ClassEntity owner=env.getLastClassOnStack();
        if(entry==null || owner==null || entry.object!=receiver || !receiver.getReflection().isInstanceOf(owner))
            throw new IllegalArgumentException("Resumed hook lost its exact declaring call entry");
        PropertyEntity declaration=owner.findProperty(property);
        var hooks=declaration==null?null:declaration.getPhpHooks();var hook=hooks==null?null:hooks.get();
        if(hook==null || hook.method().getClazz()!=owner || hook.method().getGeneratorEntity()!=generator
                || !hook.method().getName().equals(entry.function))
            throw new IllegalArgumentException("Resumed body is not the selected hook's physical generator");
        return bindGeneratedMethod(receiver,env,property,kind,hook.method().getInternalName());
    }
    private static PropertyEntity prototype(PropertyEntity property) {
        while(property.getPrototype()!=null && property.getPrototype()!=property) property=property.getPrototype();
        return property;
    }
    /** Only compiler-marked own-body accesses supply hook and original raw ALOAD0. */
    public static boolean isBackingAccess(PhpPropertyHooks.Hook hook,IObject originalReceiver,
            Environment env,IObject actualReceiver,PropertyEntity actualProperty) {
        if(hook==null || originalReceiver==null) return false;
        CallStackItem entry=env.peekCall(0);
        hook.validate();
        if(entry==null || entry.object!=originalReceiver || !hook.method().getName().equals(entry.function)
                || env.getLastClassOnStack()!=hook.method().getClazz()
                || !hook.method().getNativeMethod().getDeclaringClass().isInstance(originalReceiver))
            throw new IllegalArgumentException("Hook-body metadata is outside its executing function entry");
        return actualReceiver==originalReceiver && prototype(actualProperty)==prototype(hook.declaration());
    }
}
