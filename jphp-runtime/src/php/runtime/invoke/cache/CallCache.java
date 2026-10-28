package php.runtime.invoke.cache;

import php.runtime.env.Environment;
import php.runtime.reflection.support.Entity;

import java.lang.ref.WeakReference;

abstract public class CallCache<T extends Entity> {
    private static final ClassValue<Boolean> BASE_ROW_LOOKUP = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getCallCacheRow", CallCache.class, boolean.class).getDeclaringClass() == Environment.class;
            } catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) {
                return false;
            }
        }
    };
    private static final class RowMemo {
        final WeakReference<Environment> environment;
        final WeakReference<Row> row;
        RowMemo(Environment environment, Row row) {
            this.environment = new WeakReference<>(environment);
            this.row = new WeakReference<>(row);
        }
        boolean live() {
            Environment owner = environment.get();
            return owner != null && row.get() != null && owner.isCallCacheActive();
        }
    }
    // One immutable weak pair, never a static-cache root for row -> Entity -> Environment cycles.
    private volatile RowMemo preferredRow;

    /**
     * Retained only for binary linkage of existing Java subclasses. This matrix is no longer populated.
     * Custom storage readers must migrate to getItem(Environment, int); compiled PHP get/put ABI is unchanged.
     */
    @Deprecated
    protected Item[][] cache;

    /** An Environment-owned row; a static CallCache must never strongly retain this object. */
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
        Row row = row(env, false);
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
        Row row = row(env, true);
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

    private Row row(Environment env, boolean create) {
        RowMemo memo = preferredRow;
        if (memo != null) {
            Environment owner = memo.environment.get();
            Row remembered = memo.row.get();
            if (owner != null && remembered != null && owner.isCallCacheActive()) {
                // Other live environments keep their normal lookup without allocating replacement memos.
                return owner == env ? remembered : env.getCallCacheRow(this, create);
            }
        }
        Row resolved = env.getCallCacheRow(this, create);
        if (resolved != null && BASE_ROW_LOOKUP.get(env.getClass())) {
            synchronized (this) {
                RowMemo current = preferredRow;
                if (current == null || !current.live()) preferredRow = new RowMemo(env, resolved);
            }
        }
        return resolved;
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
