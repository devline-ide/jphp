package php.runtime.common;

import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** PHP class identifiers fold ASCII letters only; legacy symbols retain their original lookup contract. */
public final class PhpClassNames {
    // Names can come from arbitrary PHP input. Keep normalization reuse bounded,
    // independent of environments and safe across concurrent compiler/runtime threads.
    private record CanonicalName(String source, String key) {}
    private static final AtomicReferenceArray<CanonicalName> CANONICAL_NAMES = new AtomicReferenceArray<>(256);
    private PhpClassNames() {}
    /** Normalize a raw PHP API argument exactly once; internal lookup and wire names stay untouched. */
    public static String lookupName(Environment env, String rawName) {
        return rawName.startsWith("\\") && isModern(env) ? rawName.substring(1) : rawName;
    }
    public static String canonicalKey(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                int slot = name.hashCode() & (CANONICAL_NAMES.length() - 1);
                CanonicalName cached = CANONICAL_NAMES.get(slot);
                if (cached != null && cached.source.equals(name)) return cached.key;
                StringBuilder result = new StringBuilder(name);
                for (int j = i; j < name.length(); j++) {
                    char letter = name.charAt(j);
                    if (letter >= 'A' && letter <= 'Z') result.setCharAt(j, (char) (letter + ('a' - 'A')));
                }
                String key = result.toString();
                // Oversized dynamic names must not pin large input strings in the cache.
                if (name.length() <= 256) CANONICAL_NAMES.set(slot, new CanonicalName(name, key));
                return key;
            }
        }
        return name;
    }
    public static String key(String name, PhpLanguageProfile profile) {
        return profile == PhpLanguageProfile.PHP_8_5 ? canonicalKey(name) : name.toLowerCase();
    }
    public static boolean equals(String a, String b, PhpLanguageProfile profile) {
        return profile == PhpLanguageProfile.PHP_8_5 ? canonicalKey(a).equals(canonicalKey(b)) : a.equalsIgnoreCase(b);
    }
    public static boolean isModern(ClassEntity entity) {
        return entity != null && entity.usesAsciiClassNames();
    }
    public static boolean matches(ClassEntity value, String symbol, Environment env) {
        ClassEntity target = env == null ? null : env.fetchClass(symbol, false, PhpLanguageProfile.PHP_8_5);
        return value.isInstanceOfAsciiName(target == null ? symbol : target.getName());
    }

    public static boolean isModern(Environment env) {
        return env != null && env.isModernClassLookup();
    }
}
