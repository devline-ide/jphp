package php.runtime.lang;

import php.runtime.Memory;
import php.runtime.PhpEnums;
import php.runtime.annotation.Reflection.Ignore;
import php.runtime.env.Environment;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;

/** Generated PHP enum carriers inherit this class; construction requires the Environment's active case capability. */
@Ignore
public abstract class BaseEnum extends BaseObject implements php.runtime.lang.support.IComparableObject<IObject> {
    private final String caseName;
    private final Memory backingValue;
    public BaseEnum(Environment env, ClassEntity clazz) {
        super(env, clazz);
        PhpEnums.Construction capability = PhpEnums.construction(env, clazz);
        caseName = capability.name();
        backingValue = capability.value();
        php.runtime.memory.helper.PhpPropertyGuard.OwnerToken lifetime = null;
        for (var property : clazz.getProperties()) {
            ((php.runtime.memory.PhpObjectProperties)getProperties()).declare(property);
            Memory value = property.getName().equals("name") ? StringMemory.valueOf(caseName) : backingValue;
            lifetime = getProperties().putAsKeyString(property.getSpecificName(), value)
                    .attachInstancePropertySource(env, property, this, lifetime);
        }
    }
    @Ignore @Override public final boolean __equal(IObject other){return this==other;}
    @Ignore @Override public final boolean __identical(IObject other){return this==other;}
    @Ignore @Override public final boolean __greater(IObject other){return false;}
    @Ignore @Override public final boolean __greaterEq(IObject other){return this==other;}
    @Ignore @Override public final boolean __smaller(IObject other){return false;}
    @Ignore @Override public final boolean __smallerEq(IObject other){return this==other;}
    @Ignore public final String enumCaseName() { return caseName; }
    @Ignore public final Memory enumBackingValue() { return backingValue; }
}
