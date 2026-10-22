package php.runtime.lang;

import php.runtime.annotation.Reflection.BaseType;
import php.runtime.annotation.Reflection.Ignore;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.PhpObjectProperties;
import php.runtime.reflection.ClassEntity;

import java.lang.ref.WeakReference;

@Ignore
@BaseType
abstract public class BaseObject implements IObject, php.runtime.lang.support.IPhpOwnershipObject {
    protected ArrayMemory __dynamicProperties__;
    protected ClassEntity __class__;
    protected final Environment __env__;

    private volatile byte lifecycleState;
    private transient php.runtime.PhpOwnershipObjects.ObjectCertificate ownershipObject;

    @Override public final php.runtime.PhpOwnershipObjects.ObjectCertificate getOwnershipObject() {return ownershipObject;}
    @Override public final void attachOwnershipObject(php.runtime.PhpOwnershipObjects.ObjectCertificate certificate,
                                                     php.runtime.PhpOwnershipObjects.AllocationWitness witness) {
        if (ownershipObject != null) throw new IllegalStateException("Actual object already has an origin certificate");
        certificate.validateAttachment(this,witness);ownershipObject=certificate;
    }
    /** Known BaseObject field, bypassing arbitrary native overrides of getProperties(). */
    @Ignore public final PhpObjectProperties getOwnershipProperties() {
        if (__dynamicProperties__ == null) __dynamicProperties__=new PhpObjectProperties();
        if (!(__dynamicProperties__ instanceof PhpObjectProperties))
            throw new IllegalStateException("Object does not have the certified property-table representation");
        return (PhpObjectProperties)__dynamicProperties__;
    }

    public BaseObject(Environment env){
        this(env, null);
        __class__ = env.fetchClass(getClass());
    }

    protected BaseObject(ClassEntity entity) {
        this.__class__ = entity;
        this.__dynamicProperties__ = null;
        this.__env__ = null;
    }

    public BaseObject(Environment env, ClassEntity clazz) {
        this.__class__ = clazz;
        this.__dynamicProperties__ = new PhpObjectProperties();
        this.__env__ = env;
    }

    @Override
    final public int getPointer(){
        return super.hashCode();
    }

    @Override
    public ClassEntity getReflection() {
        return __class__;
    }

    /** Read existing PHP storage without initialization or arbitrary native getters. */
    @Ignore public final ArrayMemory peekProperties() {return __dynamicProperties__;}

    @Override
    public ArrayMemory getProperties() {
        if (__dynamicProperties__ == null) {
            synchronized (this) {
                if (__dynamicProperties__ != null) return __dynamicProperties__;

                if (__dynamicProperties__ == null) {
                    __dynamicProperties__ = new PhpObjectProperties();
                }
            }
        }

        return php.runtime.PhpLazyObjects.currentProperties(this,__dynamicProperties__);
    }

    @Override
    public Environment getEnvironment() {
        return __env__;
    }

    @Override
    public boolean isFinalized() {
        return (lifecycleState & 1) != 0;
    }

    @Override
    public void doFinalize() {
        lifecycleState |= 1;
    }

    @Ignore public final void beginPhpConstruction() { lifecycleState |= 2; }
    @Ignore public final void completePhpConstruction() { lifecycleState &= ~2; }
    @Ignore public final boolean isPhpConstructionPending() { return (lifecycleState & 2) != 0; }

    @Override
    public boolean isMock() {
        return __class__ == null;
    }

    @Override
    public void setAsMock() {
        __class__ = null;
    }

    @Override
    public String toString() {
        if (__class__.methodMagicToString != null) {
            Environment environment = getEnvironment();

            if (environment != null) {
                return environment.invokeMethodNoThrow(this, "__toString").toString();
            }
        }

        return super.toString();
    }
}
