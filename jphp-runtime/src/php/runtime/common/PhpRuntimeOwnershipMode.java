package php.runtime.common;

/** Generated ownership contract, independent of PHP language and numeric policy. */
public enum PhpRuntimeOwnershipMode {
    AUTO(0), CHECKED(1), OFF(2);

    private final int id;
    PhpRuntimeOwnershipMode(int id) { this.id = id; }
    public int getId() { return id; }
    public boolean supportsAdmission() { return this != OFF; }
    public static PhpRuntimeOwnershipMode fromId(int id) {
        return switch (id) {
            case 0 -> AUTO;
            case 1 -> CHECKED;
            case 2 -> OFF;
            default -> throw new IllegalArgumentException("Unknown PHP ownership mode: " + id);
        };
    }
}
