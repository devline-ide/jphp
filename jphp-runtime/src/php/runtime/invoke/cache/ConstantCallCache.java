package php.runtime.invoke.cache;

import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;

public class ConstantCallCache extends CallCache<ConstantEntity> {
    public ConstantEntity get(Environment env, int index, ClassEntity receiver, String name) {
        Item item = getItem(env, index);
        if (!(item instanceof ConstantItem)) return null;
        ConstantItem bound = (ConstantItem) item;
        return bound.receiver == receiver && bound.name.equals(name) ? (ConstantEntity) bound.data : null;
    }

    public void put(Environment env, int index, ConstantEntity constant, ClassEntity receiver, String name) {
        putItem(env, index, new ConstantItem(constant, env, receiver, name));
    }

    private static final class ConstantItem extends Item {
        private final ClassEntity receiver;
        private final String name;

        private ConstantItem(ConstantEntity constant, Environment env, ClassEntity receiver, String name) {
            super(constant, env);
            this.receiver = receiver;
            this.name = name;
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
