package php.runtime.invoke;

import java.util.List;
import java.util.ServiceLoader;

/** Optional pure kernel for proven ASCII integer append loops; null requests ordinary PHP fallback. */
public interface NumericStringKernel {
    String build(long base, long step, long count, String prefix, String suffix, boolean wrapping);

    static String execute(long base, long step, long count, String prefix, String suffix, boolean wrapping) {
        for (NumericStringKernel provider : Providers.ALL) {
            String result = provider.build(base, step, count, prefix, suffix, wrapping);
            if (result != null) return result;
        }
        return null;
    }

    final class Providers {
        private static final List<NumericStringKernel> ALL = ServiceLoader.load(
                NumericStringKernel.class, NumericStringKernel.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get).toList();
        private Providers() {}
    }
}
