package php.runtime.invoke.cache;

import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;

public class MethodCallCache extends CallCache<MethodEntity> {
    public MethodEntity get(Environment env, int index, ClassEntity receiver, String name, ClassEntity scope) {
        Item item = getItem(env, index);
        if (!(item instanceof MethodItem)) return null;
        MethodItem bound = (MethodItem) item;
        return bound.receiver == receiver && bound.scope == scope && bound.name.equals(name) ? (MethodEntity) bound.data : null;
    }

    public void put(Environment env, int index, MethodEntity method, ClassEntity receiver, String name, ClassEntity scope) {
        putItem(env, index, new MethodItem(method, env, receiver, name, scope));
    }

    private static final class MethodItem extends Item {
        private final ClassEntity receiver;
        private final ClassEntity scope;
        private final String name;

        private MethodItem(MethodEntity method, Environment env, ClassEntity receiver, String name, ClassEntity scope) {
            super(method, env);
            this.receiver = receiver;
            this.name = name;
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
