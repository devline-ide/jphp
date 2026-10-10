package php.runtime.env;

import php.runtime.common.Constants;
import php.runtime.common.PhpLanguageProfile;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/** Source compilation depends on profile and imports; a dump already owns its resolved targets. */
public final class ModuleCacheKey {
    private final String path;
    private final boolean compiled;
    private final PhpLanguageProfile profile;
    private final String importsFingerprint;

    public ModuleCacheKey(String path, boolean compiled, PhpLanguageProfile profile) {
        this(path, compiled, profile, "");
    }

    public ModuleCacheKey(String path, boolean compiled, PhpLanguageProfile profile, String importsFingerprint) {
        this.path = normalize(path);
        this.compiled = compiled;
        this.profile = compiled ? null : Objects.requireNonNull(profile);
        this.importsFingerprint = compiled ? "" : Objects.requireNonNull(importsFingerprint);
    }

    public static String normalize(String path) {
        if (path.contains("://")) return path;
        try { path = new File(path).getCanonicalPath(); }
        catch (IOException ignored) { path = new File(path).getAbsolutePath(); }
        return Constants.PATH_NAME_CASE_INSENSITIVE ? path.toLowerCase(Locale.ROOT) : path;
    }

    public String getPath() { return path; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ModuleCacheKey)) return false;
        ModuleCacheKey key = (ModuleCacheKey) other;
        return compiled == key.compiled && profile == key.profile && path.equals(key.path)
                && importsFingerprint.equals(key.importsFingerprint);
    }

    @Override public int hashCode() { return Objects.hash(path, compiled, profile, importsFingerprint); }
}
