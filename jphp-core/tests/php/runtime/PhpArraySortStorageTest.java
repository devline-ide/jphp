package php.runtime;

import org.junit.Test;
import php.runtime.common.PhpArraySort;
import java.util.*;
import static org.junit.Assert.*;

public class PhpArraySortStorageTest {
    private record Value(int key, int identity) {}

    @Test public void parallelStoragePreservesStableElementIdentity() {
        var values = new ArrayList<Value>();
        for (int i = 0; i < 2050; i++) values.add(new Value(i % 7, i));
        Collections.shuffle(values, new Random(85));
        var expected = new ArrayList<>(values);
        expected.sort(Comparator.comparingInt(Value::key));
        PhpArraySort.sort(values, Comparator.comparingInt(Value::key));
        for (int i = 0; i < values.size(); i++) assertSame(expected.get(i), values.get(i));
        var nullable = new ArrayList<>(Arrays.asList(2, null, 1, null));
        PhpArraySort.sort(nullable, Comparator.nullsFirst(Integer::compare));
        assertEquals(Arrays.asList(null, null, 1, 2), nullable);
    }

    @Test public void comparatorFailurePublishesExactPartialPermutation() {
        var values = new ArrayList<>(Arrays.asList(3, 1, 2));
        var expected = new IllegalStateException("comparison failed");
        int[] calls = {0};
        try {
            PhpArraySort.sort(values, (left, right) -> {
                if (++calls[0] == 3) throw expected;
                return Integer.compare(left, right);
            });
            fail("Comparator failure must propagate");
        } catch (IllegalStateException actual) { assertSame(expected, actual); }
        assertEquals(3, calls[0]);
        assertEquals(Arrays.asList(1, 3, 2), values);
    }
}
