package org.develnext.jphp.core.compiler.jvm;

import php.runtime.env.Environment;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Test setup only: optional physical origins and hash-pinned private fixture trees. */
final class PhpPortableTestInputs {
    private PhpPortableTestInputs() {}
    private static final String RESOURCE_ROOT = "resources/native-oracle/";
    private static final Set<Path> OWNED = new HashSet<>();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<Path> roots;
            synchronized (OWNED) { roots = new ArrayList<>(OWNED); }
            for (Path root : roots) try { removeOwned(root); }
            catch (IOException failure) { throw new UncheckedIOException("Owned test fixture cleanup failed: " + root, failure); }
        }, "jphp-portable-test-fixture-cleanup"));
    }
    static Path runtimeOrigin() throws Exception { return origin("qa.runtime", Environment.class); }
    static Path coreOrigin() throws Exception { return origin("qa.core", JvmCompiler.class); }
    private static Path origin(String property, Class<?> actual) throws Exception {
        String configured = System.getProperty(property);
        if (configured != null) return Path.of(configured).toRealPath();
        var source = actual.getProtectionDomain().getCodeSource();
        if (source == null) throw new IOException("Missing physical test origin for " + actual.getName());
        return Path.of(source.getLocation().toURI()).toRealPath();
    }
    private static byte[] resource(String name) throws IOException {
        try (var input = PhpPortableTestInputs.class.getClassLoader().getResourceAsStream(RESOURCE_ROOT + name)) {
            if (input == null) throw new IOException("Missing pinned test fixture " + name);
            return input.readAllBytes();
        }
    }
    static Path fixture(String property, String group) throws Exception {
        if (!group.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid fixture group");
        String configured = System.getProperty(property);
        Path inputRoot = configured == null ? null : Path.of(configured).toRealPath();
        byte[] catalogBytes = resource(group + "/fixture-catalog.tsv");
        Path root = Files.createTempDirectory("jphp-owned-" + group + "-").toRealPath();
        synchronized (OWNED) { OWNED.add(root); }
        Set<String> names = new HashSet<>();
        try {
            for (String line : new String(catalogBytes, StandardCharsets.UTF_8).split("\\R")) {
                if (line.isEmpty()) continue;
                String[] row = line.split("\\t", 2);
                if (row.length != 2 || !row[0].matches("[0-9a-f]{64}") || !names.add(row[1]))
                    throw new IOException("Malformed or duplicate fixture catalog row");
                Path relative = Path.of(row[1]);
                if (relative.isAbsolute() || relative.getNameCount() == 0 || !relative.normalize().equals(relative)
                        || relative.startsWith("..") || row[1].contains("\\"))
                    throw new IOException("Fixture catalog path escapes its group");
                byte[] bytes;
                if (inputRoot == null) bytes = resource(group + "/" + row[1]);
                else {
                    Path source = inputRoot.resolve(relative).toRealPath();
                    if (!source.startsWith(inputRoot)) throw new IOException("Fixture override escapes its own root");
                    bytes = Files.readAllBytes(source);
                }
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!digest.equals(row[0])) throw new IOException("Pinned fixture bytes differ: " + group + "/" + row[1]);
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) throw new IOException("Fixture target escapes its private root");
                Files.createDirectories(target.getParent());Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            }
            if (names.isEmpty()) throw new IOException("Empty fixture catalog " + group);
            return root;
        } catch (Exception | Error failure) {
            try { removeOwned(root); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    static void release(Path root) throws IOException {
        if (root != null) removeOwned(root);
    }
    private static void removeOwned(Path root) throws IOException {
        synchronized (OWNED) { if (!OWNED.contains(root)) throw new IOException("Unowned fixture cleanup request"); }
        // A replaced link cannot redirect deletion outside the exact root allocated by this helper.
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) { synchronized (OWNED) { OWNED.remove(root); } return; }
        if (!root.toRealPath().equals(root)) throw new IOException("Owned fixture root identity changed");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.toRealPath().startsWith(root)) throw new IOException("Fixture directory escaped its root");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!file.normalize().startsWith(root)) throw new IOException("Fixture file escaped its root");
                Files.delete(file);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);return FileVisitResult.CONTINUE;
            }
        });
        synchronized (OWNED) { OWNED.remove(root); }
    }
}
