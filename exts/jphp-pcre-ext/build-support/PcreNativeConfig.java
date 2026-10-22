package org.develnext.jphp.pcre.build;

import java.lang.foreign.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Runs at build time: constants describe the selected native artifact, never the compiler host. */
public final class PcreNativeConfig {
    private static String digest(Path file) throws Exception {
        var sha = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = in.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(sha.digest());
    }
    public static void main(String[] args) throws Throwable {
        if (args.length != 5) throw new IllegalArgumentException("library, expected hash, license, classifier, output required");
        Path library = Path.of(args[0]).toRealPath(), license = Path.of(args[2]).toRealPath();
        String classifier = args[3], hash = digest(library);
        if (!hash.equals(args[1]) || !hash.matches("[0-9a-f]{64}") || Files.size(license) == 0)
            throw new IllegalArgumentException("Invalid native payload or license");
        String filename = classifier.startsWith("windows-") ? "pcre2-8.dll" : classifier.startsWith("macos-") ? "libpcre2-8.dylib" : "libpcre2-8.so";
        if (!classifier.matches("(?:windows|linux|macos)-(?:x86_64|aarch64)")) throw new IllegalArgumentException("Unknown native classifier");
        String version; boolean jit;
        try (var arena = Arena.ofConfined()) {
            var symbols = SymbolLookup.libraryLookup(library, arena);
            var config = Linker.nativeLinker().downcallHandle(symbols.find("pcre2_config_8").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            var text = arena.allocate(256); var flag = arena.allocate(ValueLayout.JAVA_INT);
            int result = (int) config.invokeExact(11, text);
            if (result < 0) throw new IllegalStateException("PCRE2 version query failed: " + result);
            version = text.getString(0);
            result = (int) config.invokeExact(1, flag);
            if (result < 0) throw new IllegalStateException("PCRE2 JIT query failed: " + result);
            jit = flag.get(ValueLayout.JAVA_INT, 0) != 0;
        }
        if (!version.matches("[0-9A-Za-z. +_-]+")) throw new IllegalStateException("Invalid PCRE2 version text");
        String root = "native/" + classifier + "/";
        String data = "native.classifier=" + classifier + "\n" + classifier + ".path=" + root + filename + "\n"
                + classifier + ".sha256=" + hash + "\n" + classifier + ".license=" + root + "LICENCE-PCRE2.md\n"
                + classifier + ".license.sha256=" + digest(license) + "\n" + classifier + ".version=" + version + "\n"
                + classifier + ".jit=" + jit + "\n";
        Path output = Path.of(args[4]); Files.createDirectories(output.getParent()); Files.writeString(output, data);
        System.out.println("PCRE2_NATIVE_CONFIG " + classifier + " " + hash + " " + version + " jit=" + jit);
    }
}
