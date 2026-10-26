package php.runtime.common;

/** Numeric/code-generation contract, independent of the PHP language version. */
public enum PhpOptimizationProfile {
    COMPATIBLE(0),
    FAST_INT64_WRAP(1);

    private final int id;

    PhpOptimizationProfile(int id) { this.id = id; }

    public int getId() { return id; }

    public static PhpOptimizationProfile fromId(int id) {
        return switch (id) {
            case 0 -> COMPATIBLE;
            case 1 -> FAST_INT64_WRAP;
            default -> throw new IllegalArgumentException("Unknown PHP optimization profile: " + id);
        };
    }
}
