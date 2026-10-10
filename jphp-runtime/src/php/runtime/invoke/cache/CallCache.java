package php.runtime.invoke.cache;

import php.runtime.env.Environment;
import php.runtime.reflection.support.Entity;

import java.lang.ref.WeakReference;

abstract public class CallCache<T extends Entity> {
    /**
     * Retained only for binary linkage of existing Java subclasses. This matrix is no longer populated.
     * Custom storage readers must migrate to getItem(Environment, int); compiled PHP get/put ABI is unchanged.
     */
    @Deprecated
    protected Item[][] cache;

    /** An Environment-owned row; a static CallCache must never retain this object. */
    public static final class Row {
        private Item[] data;
        public Row() { }
    }

    @SuppressWarnings("unchecked")
    public T get(Environment env, int index) {
        Item item = getItem(env, index);
        return item == null ? null : (T) item.data;
    }

    /** Returns one stable item, including its receiver/scope metadata, under the row's monitor. */
    protected Item getItem(Environment env, int index) {
        Row row = env.getCallCacheRow(this, false);
        if (row == null) return null;
        synchronized (row) {
            Item[] data = row.data;
            if (data == null || index >= data.length) return null;
            Item item = data[index];
            if (item == null) return null;
            if (item.env.get() != env) {
                data[index] = null;
                return null;
            }
            return item;
        }
    }

    abstract public Item[] newArrayData(int length);
    /** Legacy Java linkage only: Environment IDs no longer index a cache matrix. */
    @Deprecated
    abstract public Item[][] newArrayArrayData(int length);

    @SuppressWarnings("unchecked")
    public void put(Environment env, int index, T entity) {
        putItem(env, index, new Item(entity, env));
    }

    protected void putItem(Environment env, int index, Item item) {
        Row row = env.getCallCacheRow(this, true);
        if (row == null) return; // A finalized Environment can still execute fallback calls without resurrecting cache state.
        synchronized (row) {
            Item[] data = row.data;
            if (data == null) {
                row.data = data = newArrayData(index + 1 + 10);
            } else if (index >= data.length) {
                Item[] newData = newArrayData(index + 1 + 10);
                System.arraycopy(data, 0, newData, 0, data.length);
                row.data = data = newData;
            }
            data[index] = item;
        }
    }

    protected static class Item {
        final Entity data;
        final WeakReference<Environment> env;

        public Item(Entity data, Environment env) {
            this.data = data;
            this.env = new WeakReference<Environment>(env);
        }
    }
}
