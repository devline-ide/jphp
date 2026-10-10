package php.runtime.common;

import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;

/** PHP class identifiers fold ASCII letters only; legacy symbols retain their original lookup contract. */
public final class PhpClassNames {
    private PhpClassNames() {}
    public static String canonicalKey(String name) {
        StringBuilder result = null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (result == null) result = new StringBuilder(name);
                result.setCharAt(i, (char) (c + ('a' - 'A')));
            }
        }
        return result == null ? name : result.toString();
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
