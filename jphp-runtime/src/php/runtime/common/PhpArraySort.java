package php.runtime.common;

import java.util.Comparator;
import java.util.List;

/** PHP 8.5.11's comparison schedule with original-ordinal stability.
 * Port of Zend/zend_sort.c; source attribution and licenses: PhpArraySort.LICENSE.
 * Comparators may throw: the partially permuted snapshot is returned before propagation.
 */
public final class PhpArraySort {
    private PhpArraySort() {}
    private static final class Buffer<T> {
        final T[] values;
        final int[] ordinals;
        @SuppressWarnings("unchecked")
        Buffer(List<T> source, boolean stable) {
            values = (T[]) new Object[source.size()];
            ordinals = stable ? new int[values.length] : null;
            for (int i = 0; i < values.length; i++) {
                values[i] = source.get(i);
                if (ordinals != null) ordinals[i] = i;
            }
        }
    }
    public static <T> void sort(List<T> values, Comparator<? super T> comparator) {
        sort(values, comparator, true);
    }
    /** array_unique uses Zend's comparator without the stable ordinal tie break. */
    public static <T> void sortUnstable(List<T> values, Comparator<? super T> comparator) {
        sort(values, comparator, false);
    }
    private static <T> void sort(List<T> values, Comparator<? super T> comparator, boolean stable) {
        Buffer<T> items = new Buffer<>(values, stable);
        try { sort(items, 0, items.values.length, comparator); }
        finally { for (int i = 0; i < items.values.length; i++) values.set(i, items.values[i]); }
    }
    private static <T> int compare(Buffer<T> items, int a, int b, Comparator<? super T> cmp) {
        int result = cmp.compare(items.values[a], items.values[b]);
        return result == 0 && items.ordinals != null ? Integer.compare(items.ordinals[a], items.ordinals[b]) : result;
    }
    private static <T> void swap(Buffer<T> items, int a, int b) {
        T old = items.values[a]; items.values[a] = items.values[b]; items.values[b] = old;
        if (items.ordinals != null) {
            int ordinal = items.ordinals[a]; items.ordinals[a] = items.ordinals[b]; items.ordinals[b] = ordinal;
        }
    }
    private static <T> void two(Buffer<T> items, int a, int b, Comparator<? super T> cmp) {
        if (compare(items, a, b, cmp) > 0) swap(items, a, b);
    }
    private static <T> void three(Buffer<T> items, int a, int b, int c, Comparator<? super T> cmp) {
        if (compare(items, a, b, cmp) <= 0) {
            if (compare(items, b, c, cmp) <= 0) return;
            swap(items, b, c);
            if (compare(items, a, b, cmp) > 0) swap(items, a, b);
            return;
        }
        if (compare(items, c, b, cmp) <= 0) { swap(items, a, c); return; }
        swap(items, a, b);
        if (compare(items, b, c, cmp) > 0) swap(items, b, c);
    }
    private static <T> void four(Buffer<T> items, int a, int b, int c, int d, Comparator<? super T> cmp) {
        three(items, a, b, c, cmp);
        if (compare(items, c, d, cmp) > 0) {
            swap(items, c, d);
            if (compare(items, b, c, cmp) > 0) {
                swap(items, b, c);
                if (compare(items, a, b, cmp) > 0) swap(items, a, b);
            }
        }
    }
    private static <T> void five(Buffer<T> items, int a, int b, int c, int d, int e, Comparator<? super T> cmp) {
        four(items, a, b, c, d, cmp);
        if (compare(items, d, e, cmp) > 0) {
            swap(items, d, e);
            if (compare(items, c, d, cmp) > 0) {
                swap(items, c, d);
                if (compare(items, b, c, cmp) > 0) {
                    swap(items, b, c);
                    if (compare(items, a, b, cmp) > 0) swap(items, a, b);
                }
            }
        }
    }
    private static <T> void insertion(Buffer<T> items, int start, int count, Comparator<? super T> cmp) {
        switch (count) {
            case 0, 1 -> { return; }
            case 2 -> { two(items, start, start + 1, cmp); return; }
            case 3 -> { three(items, start, start + 1, start + 2, cmp); return; }
            case 4 -> { four(items, start, start + 1, start + 2, start + 3, cmp); return; }
            case 5 -> { five(items, start, start + 1, start + 2, start + 3, start + 4, cmp); return; }
        }
        int sentry = start + 6, end = start + count;
        for (int i = start + 1; i < sentry; i++) {
            int j = i - 1;
            if (compare(items, j, i, cmp) <= 0) continue;
            while (j != start) {
                j--;
                if (compare(items, j, i, cmp) <= 0) { j++; break; }
            }
            for (int k = i; k > j; k--) swap(items, k, k - 1);
        }
        for (int i = sentry; i < end; i++) {
            int j = i - 1;
            if (compare(items, j, i, cmp) <= 0) continue;
            while (true) {
                j -= 2;
                if (compare(items, j, i, cmp) <= 0) {
                    j++;
                    if (compare(items, j, i, cmp) <= 0) j++;
                    break;
                }
                if (j == start) break;
                if (j == start + 1) {
                    j--;
                    if (compare(items, i, j, cmp) > 0) j++;
                    break;
                }
            }
            for (int k = i; k > j; k--) swap(items, k, k - 1);
        }
    }
    private static <T> void sort(Buffer<T> items, int start, int count, Comparator<? super T> cmp) {
        while (true) {
            if (count <= 16) { insertion(items, start, count, cmp); return; }
            int end = start + count, offset = count >> 1, pivot = start + offset;
            if ((count >> 10) != 0) {
                int delta = offset >> 1;
                five(items, start, start + delta, pivot, pivot + delta, end - 1, cmp);
            } else three(items, start, pivot, end - 1, cmp);
            swap(items, start + 1, pivot);
            pivot = start + 1;
            int i = pivot + 1, j = end - 1;
            partition: while (true) {
                while (compare(items, pivot, i, cmp) > 0) {
                    i++;
                    if (i == j) break partition;
                }
                j--;
                if (j == i) break;
                while (compare(items, j, pivot, cmp) > 0) {
                    j--;
                    if (j == i) break partition;
                }
                swap(items, i, j);
                i++;
                if (i == j) break;
            }
            swap(items, pivot, i - 1);
            if (i - 1 - start < end - i) {
                sort(items, start, i - start - 1, cmp);
                start = i; count = end - i;
            } else {
                sort(items, i, end - i, cmp);
                count = i - start - 1;
            }
        }
    }
}
