package php.runtime.env;

import php.runtime.common.Constants;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.common.PhpRuntimeOwnershipMode;

import java.io.*;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class Context {
    public final static Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    protected final Charset charset;
    protected final File file;
    protected String content;
    private String moduleName;
    private InputStream inputStream;
    private PhpLanguageProfile phpLanguageProfile;
    private PhpOptimizationProfile phpOptimizationProfile;
    private PhpRuntimeOwnershipMode phpOwnershipMode;
    private Boolean phpArrayOptimizationEnabled;
    private String contentModuleName;
    private Map<String, String> implicitClassImports = Collections.emptyMap();
    private String implicitClassImportsFingerprint = "";
    private boolean implicitClassImportsConfigured;
    private boolean implicitClassImportsFrozen;

    private long lastModified = 0;

    public Context(InputStream input, String fileName, Charset character){
        this.file = null;
        this.charset = character;
        this.moduleName = fileName;
        this.inputStream = input;

        if (fileName != null) {
            this.lastModified = new File(fileName).lastModified();
        }
    }

    public Context(InputStream input, Charset charset) {
        this(input, null, charset);
    }

    public Context(InputStream input) {
        this(input, null, DEFAULT_CHARSET);
    }

    public Context(File file, Charset charset) {
        this.file = file;
        this.moduleName = file.getPath();
        this.charset = charset;
        this.lastModified = file.lastModified();
    }

    public Context(File file) {
        this(file, DEFAULT_CHARSET);
    }

    public Context(String content){
        this(content, null);
    }

    public Context(String content, File file){
        this.file = file;
        this.content = content;
        this.charset = DEFAULT_CHARSET;
    }

    public long getLastModified() {
        return lastModified;
    }

    public PhpLanguageProfile getPhpLanguageProfile(CompileScope scope) {
        return phpLanguageProfile == null ? (scope == null ? PhpLanguageProfile.JPHP_LEGACY : scope.getPhpLanguageProfile()) : phpLanguageProfile;
    }

    public void setPhpLanguageProfile(PhpLanguageProfile profile) {
        phpLanguageProfile = profile;
    }

    public PhpOptimizationProfile getPhpOptimizationProfile(CompileScope scope) {
        return phpOptimizationProfile == null ? (scope == null ? PhpOptimizationProfile.COMPATIBLE : scope.getPhpOptimizationProfile()) : phpOptimizationProfile;
    }

    public void setPhpOptimizationProfile(PhpOptimizationProfile profile) {
        phpOptimizationProfile = profile;
    }

    public PhpRuntimeOwnershipMode getPhpOwnershipMode(CompileScope scope) {
        return phpOwnershipMode == null ? (scope == null ? PhpRuntimeOwnershipMode.AUTO : scope.getPhpOwnershipMode()) : phpOwnershipMode;
    }

    public void setPhpOwnershipMode(PhpRuntimeOwnershipMode mode) { phpOwnershipMode = mode; }

    public boolean isPhpArrayOptimizationEnabled(CompileScope scope) {
        return phpArrayOptimizationEnabled == null ? scope != null && scope.isPhpArrayOptimizationEnabled() : phpArrayOptimizationEnabled;
    }

    public void setPhpArrayOptimizationEnabled(boolean enabled) { phpArrayOptimizationEnabled = enabled; }

    /**
     * Optional CLASS imports for this source only. Aliases are the class basenames.
     * Configure before compilation; explicit PHP use declarations take precedence.
     * The immutable snapshot never changes PHP source bytes or diagnostic locations.
     */
    public synchronized void setImplicitClassImports(Collection<String> classNames) {
        Objects.requireNonNull(classNames, "classNames");
        SortedMap<String, String> imports = new TreeMap<>();
        SortedSet<String> names = new TreeSet<>();
        for (String name : classNames) {
            Objects.requireNonNull(name, "className");
            String normalized = name.startsWith("\\") ? name.substring(1) : name;
            validateImplicitClassName(normalized);
            names.add(normalized);
        }
        for (String name : names) {
            String alias = normalizeClassImportAlias(name.substring(name.lastIndexOf('\\') + 1));
            if (Set.of("self", "parent", "static", "array", "callable", "bool", "float", "int", "string",
                    "object", "iterable", "mixed", "void", "never", "true", "false", "null").contains(alias)) {
                throw new IllegalArgumentException("Cannot import special class alias: " + alias);
            }
            String previous = imports.putIfAbsent(alias, name);
            if (previous != null) {
                throw new IllegalArgumentException("Conflicting implicit class alias " + alias + ": " + previous + " and " + name);
            }
        }
        if (implicitClassImportsConfigured || implicitClassImportsFrozen) {
            if (implicitClassImports.equals(imports)) return;
            throw new IllegalStateException("Implicit class imports have already been configured or frozen");
        }
        implicitClassImports = Collections.unmodifiableMap(imports);
        if (!imports.isEmpty()) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (Map.Entry<String, String> entry : imports.entrySet()) {
                    updateImportFingerprint(digest, entry.getKey());
                    updateImportFingerprint(digest, entry.getValue());
                }
                implicitClassImportsFingerprint = HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        implicitClassImportsConfigured = true;
    }

    /** PHP class aliases fold ASCII letters; non-ASCII identifier bytes remain distinct. */
    public static String normalizeClassImportAlias(String name) {
        StringBuilder normalized = null;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                if (normalized == null) normalized = new StringBuilder(name);
                normalized.setCharAt(i, (char) (ch + ('a' - 'A')));
            }
        }
        return normalized == null ? name : normalized.toString();
    }

    private static void updateImportFingerprint(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(DEFAULT_CHARSET);
        int size = bytes.length;
        digest.update(new byte[] {(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
        digest.update(bytes);
    }

    private static void validateImplicitClassName(String name) {
        // PHP identifiers allow every non-ASCII UTF-8 byte, including Unicode digits.
        // DEL, empty segments, ASCII leading digits and malformed UTF-16 are invalid.
        boolean first = true;
        if (name.isEmpty()) throw new IllegalArgumentException("Empty implicit class name");
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '\\') {
                if (first) throw new IllegalArgumentException("Invalid implicit class name: " + name);
                first = true;
                continue;
            }
            if (Character.isHighSurrogate(ch)) {
                if (++i == name.length() || !Character.isLowSurrogate(name.charAt(i))) {
                    throw new IllegalArgumentException("Malformed Unicode implicit class name");
                }
            } else if (Character.isLowSurrogate(ch) || !(ch >= 128 || ch == '_'
                    || ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z'
                    || !first && ch >= '0' && ch <= '9')) {
                throw new IllegalArgumentException("Invalid implicit class name: " + name);
            }
            first = false;
        }
        if (first) throw new IllegalArgumentException("Invalid implicit class name: " + name);
    }

    public Map<String, String> getImplicitClassImports() { return implicitClassImports; }

    public String getImplicitClassImportsFingerprint() { return implicitClassImportsFingerprint; }

    /** Freeze even the default empty configuration before cache lookup or parsing. */
    public synchronized void freezeImplicitClassImports() { implicitClassImportsFrozen = true; }

    protected void readContent(Reader reader) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(reader);
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            result.append(line);
            result.append("\n");
        }
        this.content = result.toString();
        this.contentModuleName = null;
    }

    public boolean isLikeFile(){
        return file != null || inputStream != null;
    }

    public String getFileName(){
        return file != null ? file.getPath() : moduleName;
    }

    public String getModuleName() throws IOException {
        if (moduleName != null && file == null)
            return moduleName;

        if (file == null) {
            if (content == null)
                return null;
            if (contentModuleName == null) {
                try {
                    contentModuleName = "sha256~" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(DEFAULT_CHARSET)));
                } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            }
            return implicitClassImportsFingerprint.isEmpty() ? contentModuleName
                    : contentModuleName + "~imports~" + implicitClassImportsFingerprint;
        } else {
            String name = file.getCanonicalPath();
            if (Constants.PATH_NAME_CASE_INSENSITIVE)
                name = name.toLowerCase(Locale.ROOT);

            return name;
        }
    }

    public String getModuleNameNoThrow(){
        try {
           return getModuleName();
        } catch (IOException e){
           return file.getAbsolutePath();
        }
    }

    public String getContent() throws IOException {
        if (inputStream != null){
            readContent(new InputStreamReader(inputStream, charset));
        } else if (content == null && file != null){
            try (InputStream input = new FileInputStream(file)) {
                readContent(new InputStreamReader(input, charset));
            }
        }
        return content;
    }

    public InputStream getInputStream(Charset charset) throws IOException {
        if (inputStream != null)
            return inputStream;
        else if (file != null)
            return new FileInputStream(file);
        else
            return new ByteArrayInputStream(content.getBytes(charset));
    }
}
