package php.runtime.common;

/** Language semantics target, independent of JPHP's error-handling LangMode. */
public enum PhpLanguageProfile {
    JPHP_LEGACY(0),
    PHP_8_5(80500);

    private final int id;

    PhpLanguageProfile(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static PhpLanguageProfile fromId(int id) {
        for (PhpLanguageProfile profile : values()) {
            if (profile.id == id) return profile;
        }
        throw new IllegalArgumentException("Unknown PHP language profile: " + id);
    }
}
