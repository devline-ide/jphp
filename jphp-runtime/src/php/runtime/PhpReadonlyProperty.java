package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseError;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.helper.PhpPropertyGuard;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Declaration guards and bounded clone/Reflection privileges; no instance ownership census. */
public final class PhpReadonlyProperty {
    private PhpReadonlyProperty() {}
    private static final ThreadLocal<CloneFrame> CLONE=new ThreadLocal<>();
    private static final ThreadLocal<Permit> PERMIT=new ThreadLocal<>();
    public static final class CloneFrame {
        final IObject object;final CloneFrame previous;
        final Set<PropertyEntity> written=Collections.newSetFromMap(new IdentityHashMap<>());
        CloneFrame(IObject object,CloneFrame previous){this.object=object;this.previous=previous;}
    }
    private record Permit(ReferenceMemory address,PropertyEntity property,IObject object,boolean reference,Permit previous) {}
    public static CloneFrame enterClone(IObject actual) {
        boolean needed=false;
        for(PropertyEntity property:actual.getReflection().getProperties()) if(property.isPhpReadonly()){needed=true;break;}
        if(!needed)return null;var frame=new CloneFrame(actual,CLONE.get());CLONE.set(frame);return frame;
    }
    public static void leaveClone(CloneFrame frame) {
        if(frame==null)return;if(CLONE.get()!=frame)throw new IllegalStateException("Unbalanced readonly clone context");
        if(frame.previous==null)CLONE.remove();else CLONE.set(frame.previous);
    }
    private static boolean cloneAllows(PropertyEntity property,IObject actual) {
        var frame=CLONE.get();return frame!=null && frame.object==actual && !frame.written.contains(property);
    }
    private static boolean cloneAllows(PhpPropertyGuard.Source source) {
        var frame=CLONE.get();return frame!=null && source.ownerToken()!=null && source.ownerToken().matches(frame.object) && !frame.written.contains(source.property);
    }
    private static void scope(Environment env,TraceInfo trace,PropertyEntity property,String operation) {
        ClassEntity caller=env.getLastClassOnStack(),declared=property.getClazz();
        boolean allowed=PhpPropertyAccess.allowed(property,caller);
        if(property.getPhpSetModifier()!=null) {PhpPropertyAccess.check(property,env,trace,PhpPropertyAccess.Intent.ASSIGN);if(allowed)return;}
        if(!allowed)env.exception(trace,BaseError.class,"Cannot %s %s(set) readonly property %s::$%s from %s scope",operation,
                property.effectiveSetModifier()==php.runtime.common.Modifier.PRIVATE?"private":"protected",declared.getName(),property.getName(),caller==null?"global":caller.getName());
    }
    public static void checkWrite(Environment env,TraceInfo trace,PropertyEntity property,IObject object,Memory before) {
        if(!property.isPhpReadonly())return;
        if(before!=null && !before.isUndefined() && !cloneAllows(property,object))
            env.exception(trace,BaseError.class,"Cannot modify readonly property %s::$%s",property.getClazz().getName(),property.getName());
        scope(env,trace,property,"modify");
    }
    public static void checkWrite(PhpPropertyGuard.Source source,Memory before) {
        var permit=PERMIT.get();
        if(permit!=null && !permit.reference && permit.property==source.property && source.ownerToken()!=null && source.ownerToken().matches(permit.object)) {
            if(before!=null && !before.isUndefined() && !cloneAllows(source))
                source.writer().exception(source.trace(),BaseError.class,"Cannot modify readonly property %s",source.label());
            return;
        }
        if(before!=null && !before.isUndefined() && !cloneAllows(source))
            source.writer().exception(source.trace(),BaseError.class,"Cannot modify readonly property %s",source.label());
        scope(source.writer(),source.trace(),source.property,"modify");
    }
    public static void wrote(PhpPropertyGuard.Source source) {
        var frame=CLONE.get();if(frame!=null && source.ownerToken()!=null && source.ownerToken().matches(frame.object))frame.written.add(source.property);
    }
    public static void checkUnset(Environment env,TraceInfo trace,PropertyEntity property,IObject object,Memory before) {
        if(before!=null && !before.isUndefined() && !cloneAllows(property,object))
            env.exception(trace,BaseError.class,"Cannot unset readonly property %s::$%s",property.getClazz().getName(),property.getName());
        scope(env,trace,property,"unset");
    }
    public static void indirect(PhpPropertyGuard.Source source,ReferenceMemory actual) {
        var permit=PERMIT.get();if(permit!=null && permit.reference && permit.address==actual)return;
        source.writer().exception(source.trace(),BaseError.class,"Cannot indirectly modify readonly property %s",source.label());
    }
    public static Memory reflectAssign(Environment env,TraceInfo trace,PropertyEntity property,IObject object,ReferenceMemory address,Memory value) {
        var previous=PERMIT.get();PERMIT.set(new Permit(address,property,object,false,previous));
        try{return address.assign(value);}finally{if(previous==null)PERMIT.remove();else PERMIT.set(previous);}
    }
    public static ReferenceMemory readerReference(ReferenceMemory actual) {
        var previous=PERMIT.get();PERMIT.set(new Permit(actual,null,null,true,previous));
        try{return actual.getReferenceForBinding();}finally{if(previous==null)PERMIT.remove();else PERMIT.set(previous);}
    }
}
