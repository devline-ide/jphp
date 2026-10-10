package php.runtime.invoke.cache;

import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.ClassEntity;
import php.runtime.env.Environment;

public class PropertyCallCache extends CallCache<PropertyEntity> {
    public PropertyEntity get(Environment env, int index, ClassEntity receiver, ClassEntity scope) {
        Item item = getItem(env, index);
        if (!(item instanceof ScopedItem)) return null;
        ScopedItem scoped = (ScopedItem) item;
        return scoped.receiver == receiver && scoped.scope == scope ? (PropertyEntity) scoped.data : null;
    }

    public void put(Environment env, int index, PropertyEntity entity, ClassEntity receiver, ClassEntity scope) {
        putItem(env, index, new ScopedItem(entity, env, receiver, scope));
    }

    public int getDynamicOffset(Environment env, int index, ClassEntity receiver, ClassEntity scope, String name) {
        Item item = getItem(env, index);
        if (!(item instanceof DynamicItem)) return -1;
        DynamicItem dynamic = (DynamicItem) item;
        return dynamic.receiver == receiver && dynamic.scope == scope && dynamic.name.equals(name) ? dynamic.ordinal : -1;
    }

    public void putDynamicOffset(Environment env, int index, ClassEntity receiver, ClassEntity scope, String name, int ordinal) {
        putItem(env, index, new DynamicItem(env, receiver, scope, name, ordinal));
    }

    private static final class DynamicItem extends Item {
        private final ClassEntity receiver;
        private final ClassEntity scope;
        private final String name;
        private final int ordinal;
        private DynamicItem(Environment env, ClassEntity receiver, ClassEntity scope, String name, int ordinal) {
            super(null, env);
            this.receiver = receiver; this.scope = scope; this.name = name; this.ordinal = ordinal;
        }
    }

    private static final class ScopedItem extends Item {
        private final ClassEntity receiver;
        private final ClassEntity scope;

        private ScopedItem(PropertyEntity entity, Environment env, ClassEntity receiver, ClassEntity scope) {
            super(entity, env);
            this.receiver = receiver;
            this.scope = scope;
        }
    }

    @Override
    public Item[] newArrayData(int length) {
        return new Item[length];
    }

    @Override
    public Item[][] newArrayArrayData(int length) {
        return new Item[length][];
    }
}
