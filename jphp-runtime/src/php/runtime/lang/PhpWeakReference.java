package php.runtime.lang;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.support.ICloneableObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;

/** PHP weak identity, backed by JVM reachability; both registry endpoints are weak. */
@Name("WeakReference")
@NativePhpSignature
public final class PhpWeakReference extends BaseObject implements ICloneableObject<PhpWeakReference> {
    private final Key target;
    private final Registry registry;

    public PhpWeakReference(Environment env, ClassEntity clazz) {
        super(env, clazz); target=null;registry=null;
        prohibitedConstruction(env);
    }
    private PhpWeakReference(Environment env, Key target, Registry registry) {
        super(env,env.fetchClass(PhpWeakReference.class));this.target=target;this.registry=registry;
    }
    private static void prohibitedConstruction(Environment env) {
        env.exception(BaseError.class,"Direct instantiation of WeakReference is not allowed, use WeakReference::create instead");
    }
    @Signature public void __construct(Environment env) { prohibitedConstruction(env); }
    @Signature(value=@Arg(value="object",type=HintType.OBJECT),result=@Arg(typeClass="WeakReference"))
    public static Memory create(Environment env,Memory... args) {
        IObject object=args[0].toValue(ObjectMemory.class).value;Registry registry;
        synchronized(env) {
            registry=env.getUserValue(Registry.class);
            if(registry==null){registry=new Registry();env.setUserValue(registry);}
        }
        return ObjectMemory.valueOf(registry.create(env,object));
    }
    @Signature(result=@Arg(type=HintType.OBJECT,nullable=true))
    public Memory get() {
        registry.drain();IObject object=target.get();return object==null?Memory.NULL:ObjectMemory.valueOf(object);
    }
    @Ignore public ArrayMemory weakDebugInfo() {
        var data=new ArrayMemory();data.put("object",get());return data;
    }
    @Override @Ignore public PhpWeakReference __clone(Environment env,TraceInfo trace) {
        env.exception(trace,BaseError.class,"Trying to clone an uncloneable object of class WeakReference");return null;
    }
    private static final class Key extends WeakReference<IObject> {
        final int hash;
        Key(IObject value,ReferenceQueue<IObject> queue){super(value,queue);hash=System.identityHashCode(value);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){if(this==other)return true;IObject object=get();return object!=null&&other instanceof Key key&&object==key.get();}
    }
    private static final class Entry extends WeakReference<PhpWeakReference> {
        final Key key;
        Entry(PhpWeakReference value,ReferenceQueue<PhpWeakReference> queue,Key key){super(value,queue);this.key=key;}
    }
    private static final class Registry {
        private final ReferenceQueue<IObject> objects=new ReferenceQueue<>();
        private final ReferenceQueue<PhpWeakReference> wrappers=new ReferenceQueue<>();
        private final Map<Key,Entry> entries=new HashMap<>();
        synchronized void drain() {
            Key object;while((object=(Key)objects.poll())!=null)entries.remove(object);
            Entry wrapper;while((wrapper=(Entry)wrappers.poll())!=null)entries.remove(wrapper.key,wrapper);
        }
        synchronized PhpWeakReference create(Environment env,IObject object) {
            drain();var key=new Key(object,objects);var previous=entries.get(key);var value=previous==null?null:previous.get();
            if(value!=null)return value;
            value=new PhpWeakReference(env,key,this);entries.put(key,new Entry(value,wrappers,key));return value;
        }
    }
}
