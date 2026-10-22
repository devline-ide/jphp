package org.develnext.jphp.pcre;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/** Unpacked, classifier-specific distribution payload; never searches PATH or system libraries. */
final class PcreNativePackage {
    record Metadata(Path jar,String classifier,String relative,String sha256,String version,boolean jit) {}
    static Path verified(Path path, String expectedHash) throws Exception {
        path = path.toAbsolutePath().normalize();
        if (!expectedHash.matches("[0-9a-f]{64}") || !Files.isRegularFile(path) || Files.isSymbolicLink(path))
            throw new IllegalArgumentException("Invalid pinned PCRE2 native payload");
        String actual;
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            actual = HexFormat.of().formatHex(digest.digest());
        }
        if (!actual.equals(expectedHash)) throw new IllegalStateException("PCRE2 native payload hash mismatch");
        return path;
    }
    /** Reads only the selected artifact's build-time configuration; never probes the compiler host. */
    static Metadata metadata() throws Exception {
        Path jar = Path.of(PcreExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("Packaged PCRE2 requires a selected extension JAR");
        Properties manifest = new Properties();
        try (JarFile selected = new JarFile(jar.toFile())) {
            var entry = selected.getJarEntry("META-INF/jphp-pcre/native.properties");
            if (entry == null) throw new IllegalStateException("Missing declared jphp-pcre native package manifest");
            try (InputStream in = selected.getInputStream(entry)) {
                byte[] bytes=in.readNBytes(8193);
                if(bytes.length>8192)throw new IllegalStateException("PCRE2 native manifest is too large");
                try(InputStream bounded=new java.io.ByteArrayInputStream(bytes)){manifest.load(bounded);}
            }
        }
        String classifier=manifest.getProperty("native.classifier", "");
        if(!Set.of("windows-x86_64","windows-aarch64","linux-x86_64","linux-aarch64","macos-x86_64","macos-aarch64").contains(classifier))
            throw new IllegalStateException("Unsupported declared PCRE2 native classifier: "+classifier);
        String basename=classifier.startsWith("windows-")?"pcre2-8.dll":classifier.startsWith("linux-")?"libpcre2-8.so":"libpcre2-8.dylib";
        String relative=manifest.getProperty(classifier+".path", "");
        if(!("native/"+classifier+"/"+basename).equals(relative))throw new IllegalStateException("Unexpected native package path");
        String hash=manifest.getProperty(classifier+".sha256", "");
        if(!hash.matches("[0-9a-f]{64}"))throw new IllegalStateException("Missing PCRE2 native payload hash");
        String version=manifest.getProperty(classifier+".version", "");
        if(!version.matches("[0-9]+\\.[0-9]+(?: [ -~]+)?")||version.length()>128)
            throw new IllegalStateException("Missing PCRE2 build-time version");
        String jit=manifest.getProperty(classifier+".jit", "");
        if(!jit.equals("true")&&!jit.equals("false"))throw new IllegalStateException("Missing PCRE2 build-time JIT configuration");
        return new Metadata(jar,classifier,relative,hash,version,Boolean.parseBoolean(jit));
    }
    static void requireHost(Metadata metadata) {
        String os=System.getProperty("os.name", "").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform=os.startsWith("windows")?"windows":os.startsWith("linux")?"linux":os.startsWith("mac")||os.startsWith("darwin")?"macos":"";
        String architecture=arch.equals("amd64")||arch.equals("x86_64")?"x86_64":arch.equals("aarch64")||arch.equals("arm64")?"aarch64":"";
        String actual=platform+"-"+architecture;
        if(platform.isEmpty()||architecture.isEmpty()||!actual.equals(metadata.classifier()))
            throw new UnsupportedOperationException("PCRE2 package target "+metadata.classifier()+" does not match runtime "+os+"/"+arch);
    }
    static Path packaged(Metadata metadata) throws Exception {
        Path root = metadata.jar().getParent().toRealPath(); Path nativeFile = root.resolve(metadata.relative()).normalize();
        if (!nativeFile.toRealPath().startsWith(root)) throw new IllegalStateException("PCRE2 native package escapes selected lib directory");
        return verified(nativeFile, metadata.sha256());
    }
}
