package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import static org.junit.Assert.*;

/** Physical include acceptance. Expected event prefixes are pinned real native output. */
public class PhpDeprecatedPhysicalEffectsTest {
    private static final String INPUT_MANIFEST_SHA = "dac8038388662bea93c36c2aca24f4899a9ec5209734cf8bda871bebec7c83a6";
    private static Path fixtures;
    private static Path evidence;
    private static Path ownedFixtures;

    private record Case(String name, String consumer, List<String> fragments, boolean fatalPrefix) {
        List<String> files() {
            List<String> result = new ArrayList<>();
            result.add(name + ".php"); result.addAll(fragments); return result;
        }
    }
    private record Fetch(String request, String sha256, boolean compiled, int compilerAbi,
                         String origin, String profile, String module, String moduleContext) {}

    private static synchronized void inputs() throws Exception {
        if (fixtures != null) return;
        String input = System.getProperty("qa.php.deprecated.effects.inputs");
        String output = System.getProperty("qa.php.deprecated.effects.evidence");
        if (input == null || output == null)
            ownedFixtures = PhpPortableTestInputs.fixture("qa.php.deprecated.effects.inputs", "php-deprecated-physical-effects");
        Path candidate = input == null ? ownedFixtures : Path.of(input).toRealPath();
        Path manifest = candidate.resolve("INPUTS.sha256");
        assertEquals("Frozen input manifest", INPUT_MANIFEST_SHA, sha(manifest));
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] pair = line.split("  ", 2);
            assertEquals("Manifest row", 2, pair.length);
            Path file = candidate.resolve(pair[1]).normalize();
            assertTrue("Input stays within fixture directory", file.startsWith(candidate));
            assertTrue("Input real path stays within fixture directory", file.toRealPath().startsWith(candidate));
            assertEquals(pair[1], pair[0], sha(file));
        }
        evidence = output == null ? Files.createDirectory(ownedFixtures.resolve("test-evidence")) : Path.of(output).toRealPath();
        fixtures = candidate;
    }

    @org.junit.AfterClass public static synchronized void releaseInputs() throws Exception {
        if (ownedFixtures != null) {
            PhpPortableTestInputs.release(ownedFixtures);
            ownedFixtures = null; fixtures = null; evidence = null;
        }
    }

    private static CompileScope scope(boolean debug) {
        CompileScope scope = new CompileScope();
        scope.setDebugMode(debug);
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new php.runtime.ext.CoreExtension());
        scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        return scope;
    }

    /** Observer preserves real import/registration dispatch. No fake resolver or preloaded runtime fixture. */
    private static final class TrackedEnvironment extends Environment {
        private final Path root;
        private final boolean phb;
        private final Set<Path> expected;
        final List<Fetch> fetched = new ArrayList<>();
        final List<String> attempts = new ArrayList<>();
        TrackedEnvironment(CompileScope scope, ByteArrayOutputStream output, Path root,
                           boolean phb, Set<Path> expected) throws Exception {
            super(scope, output);
            this.root = root.toRealPath(); this.phb = phb; this.expected = expected;
            getModuleManager().setSourceLanguageProfileResolver(path -> {
                if (phb) throw new AssertionError("SOURCE fetch in PHB-only runtime: " + path);
                return PhpLanguageProfile.PHP_8_5;
            });
        }
        private Path before(Context context, boolean compiled) throws Exception {
            Path path = Path.of(context.getFileName()).toRealPath();
            attempts.add((compiled ? "PHB " : "SOURCE ") + path);
            assertTrue("Physical fixture fetch only: " + path, path.startsWith(root));
            assertTrue("Exact required physical file: " + path, expected.contains(path));
            assertEquals("Requested lane: " + path, phb, compiled);
            return path;
        }
        private ModuleEntity after(Path path, ModuleEntity module, boolean compiled) throws Exception {
            fetched.add(new Fetch(path.toString(), sha(path), module.isCompiledArtifact(),
                    module.getCompilerProvenance().getCompilerAbiVersion(),
                    module.getCompilerProvenance().getOrigin().name(), module.getPhpLanguageProfile().name(),
                    module.getInternalName(), module.getContext().getFileName()));
            assertEquals("Actual loaded artifact: " + path, compiled, module.isCompiledArtifact());
            assertEquals("Stored declaration profile", PhpLanguageProfile.PHP_8_5, module.getPhpLanguageProfile());
            assertTrue("Current compiler provenance", module.getCompilerProvenance().getCompilerAbiVersion() >= 29);
            return module;
        }
        @Override public ModuleEntity importModule(Context context) throws Throwable {
            if (root == null) return super.importModule(context); // Environment constructor has not enabled observation yet.
            Path path = before(context, false);
            return after(path, super.importModule(context), false);
        }
        @Override public ModuleEntity importCompiledModule(Context context, boolean debug) throws Throwable {
            if (root == null) return super.importCompiledModule(context, debug);
            Path path = before(context, true);
            return after(path, super.importCompiledModule(context, debug), true);
        }
    }

    private static Context context(Path file) throws Exception {
        Context context = new Context(Files.readString(file, StandardCharsets.UTF_8), file.toFile());
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        return context;
    }

    /** Every PHP input physically exists during compile, then only its owned, hash-checked source is removed. */
    private static List<String> prepare(Case test, Path cell, Path runtime, boolean debug, boolean phb) throws Throwable {
        List<String> prepared = new ArrayList<>();
        Map<Path, String> ownedSources = new LinkedHashMap<>();
        for (String relative : test.files()) {
            Path input = fixtures.resolve("source").resolve(relative);
            String original = Files.readString(input, StandardCharsets.UTF_8);
            String source = original;
            if (phb) for (String fragment : test.fragments()) {
                String originalLiteral = "/" + fragment;
                source = source.replace(originalLiteral, originalLiteral.substring(0, originalLiteral.length() - 4) + ".phb");
            }
            Path target = runtime.resolve(relative).normalize();
            assertTrue(target.startsWith(runtime));
            Files.createDirectories(target.getParent());
            Files.writeString(target, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            ownedSources.put(target, sha(target));
            prepared.add(relative + " original=" + sha(input) + " transformed=" + sha(target));
        }
        if (!phb) return prepared;

        ByteArrayOutputStream preparationOutput = new ByteArrayOutputStream();
        Environment preparation = new Environment(scope(debug), preparationOutput);
        Throwable preparationFailure = null;
        try {
            List<String> order = new ArrayList<>();
            for (String fragment : test.fragments()) if (fragment.endsWith("/definitions.php")) order.add(fragment);
            for (String fragment : test.fragments()) if (!order.contains(fragment)) order.add(fragment);
            order.add(test.name() + ".php");
            for (String relative : order) {
                Path source = runtime.resolve(relative);
                Context context = context(source);
                ModuleEntity module = new JvmCompiler(preparation, context).compile(false);
                assertFalse(module.isCompiledArtifact());
                assertEquals(29, module.getCompilerProvenance().getCompilerAbiVersion());
                Path dump = runtime.resolve(relative.substring(0, relative.length() - 4) + ".phb");
                try (var output = Files.newOutputStream(dump, StandardOpenOption.CREATE_NEW)) {
                    new ModuleDumper(context, preparation, debug).save(module, output);
                }
                prepared.add(relative + " -> " + runtime.relativize(dump) + " phb=" + sha(dump)
                        + " ABI=" + module.getCompilerProvenance().getCompilerAbiVersion()
                        + " debug=" + debug + " module=" + module.getInternalName());
                if (relative.endsWith("/definitions.php")) {
                    // Metadata only. No include/eval, and the runtime uses an entirely different scope below.
                    preparation.getScope().loadModule(module);
                    preparation.getScope().addUserModule(module);
                    preparation.registerModule(module);
                }
            }
            assertEquals("Preparation executes no fixture PHP", 0, preparationOutput.size());
            if (test.consumer() != null)
                assertNull("Consumer was never included during precompile", preparation.fetchClass(test.consumer(), false, PhpLanguageProfile.PHP_8_5));
        } catch (Throwable failure) { preparationFailure = failure; throw failure; }
        finally {
            try { preparation.doFinal(); }
            catch (Throwable cleanup) { if (preparationFailure == null) throw cleanup; preparationFailure.addSuppressed(cleanup); }
            Files.write(cell.resolve("preparation.txt"), prepared, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        for (Map.Entry<Path, String> source : ownedSources.entrySet()) {
            assertTrue(source.getKey().toRealPath().startsWith(runtime.toRealPath()));
            assertEquals("Delete only this harness's unchanged source", source.getValue(), sha(source.getKey()));
            Files.delete(source.getKey());
        }
        try (var files = Files.walk(runtime)) {
            assertFalse("No PHP sources in PHB runtime fixture", files.anyMatch(path -> path.toString().endsWith(".php")));
        }
        return prepared;
    }

    private static void run(Case test) throws Throwable {
        inputs();
        Throwable failed = null;
        for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            try { runCell(test, debug, phb); }
            catch (Throwable failure) {
                if (failed == null) failed = failure;
                else if (failure != failed) failed.addSuppressed(failure);
            }
        }
        if (failed != null) throw failed;
    }

    private static void runCell(Case test, boolean debug, boolean phb) throws Throwable {
            Path cell = evidence.resolve(test.name() + "-debug" + debug + "-" + (phb ? "PHB" : "SOURCE"));
            Files.createDirectory(cell);
            Path runtime = Files.createDirectory(cell.resolve("runtime"));
            List<String> prepared = prepare(test, cell, runtime, debug, phb);
            if (!phb) Files.write(cell.resolve("preparation.txt"), prepared, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Set<Path> expected = new HashSet<>();
            for (String relative : test.files()) expected.add(runtime.resolve(phb
                    ? relative.substring(0, relative.length() - 4) + ".phb" : relative).toRealPath());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            TrackedEnvironment env = new TrackedEnvironment(scope(debug), output, runtime, phb, expected);
            Throwable executionFailure = null, visibilityFailure = null;
            boolean visible = false;
            try {
                if (test.consumer() != null) assertNull("Fresh runtime did not inherit preparation metadata",
                        env.fetchClass(test.consumer(), false, PhpLanguageProfile.PHP_8_5));
                Path entry = runtime.resolve(test.name() + (phb ? ".phb" : ".php"));
                ModuleEntity module;
                if (phb) {
                    Context context = new Context(entry.toFile()); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    module = env.importCompiledModule(context, debug);
                } else module = env.importModule(context(entry));
                module.include(env);
            } catch (Throwable failure) { executionFailure = failure; }
            finally {
                if (test.consumer() != null) try { visible = env.fetchClass(test.consumer(), false, PhpLanguageProfile.PHP_8_5) != null; }
                catch (Throwable failure) { visibilityFailure = failure; }
                try { env.doFinal(); }
                catch (Throwable cleanup) { if (executionFailure == null) executionFailure = cleanup; else executionFailure.addSuppressed(cleanup); }
                try { env.flushAll(); }
                catch (Throwable cleanup) { if (executionFailure == null) executionFailure = cleanup; else executionFailure.addSuppressed(cleanup); }
                Files.write(cell.resolve("actual.stdout"), output.toByteArray(), StandardOpenOption.CREATE_NEW);
                Files.writeString(cell.resolve("failure.txt"), stack(executionFailure) + stack(visibilityFailure), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                Files.write(cell.resolve("fetch-attempts.txt"), env.attempts, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                writeProof(cell, env.fetched, expected, visible, debug, phb);
            }
            Set<Path> loaded = new HashSet<>();
            for (Fetch fetch : env.fetched) loaded.add(Path.of(fetch.request()).toRealPath());
            assertEquals("Every physical entry/include was really imported, including the inner PHB", expected, loaded);
            assertNull("Visibility query must succeed", visibilityFailure);
            byte[] nativeBytes = Files.readAllBytes(fixtures.resolve("native/" + test.name() + ".stdout"));
            String actual = output.toString(StandardCharsets.UTF_8);
            if (test.fatalPrefix()) {
                String nativeText = new String(nativeBytes, StandardCharsets.UTF_8);
                int shutdown = nativeText.indexOf("shutdown=");
                assertTrue("Bound native fatal prefix", shutdown > 0);
                String prefix = nativeText.substring(0, shutdown);
                assertTrue("Exact native event prefix; see " + cell + "\nEXPECTED:\n" + prefix + "ACTUAL:\n" + actual, actual.startsWith(prefix));
                for (String forbidden : List.of("require-returned", "fragment-after", "inner-after=", "reentry-after="))
                    assertFalse("Fatal declaration cannot continue: " + forbidden, actual.contains(forbidden));
                assertFalse("Failed consumer stays unpublished", visible);
                assertTrue("Raw failure captured without rewriting fatal wording", executionFailure != null || actual.contains("caught="));
            } else {
                assertNull("Successful oracle case failed; see " + cell, executionFailure);
                assertEquals("Successful native stderr is empty", 0, Files.size(fixtures.resolve("native/" + test.name() + ".stderr")));
                assertArrayEquals("Exact native stdout; known baseline differences stay FAIL: " + cell, nativeBytes, output.toByteArray());
            }
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static String stack(Throwable failure) {
        if (failure == null) return "";
        StringWriter text = new StringWriter(); failure.printStackTrace(new PrintWriter(text)); return text.toString();
    }
    private static String json(String text) {
        if (text == null) return "null";
        StringBuilder result = new StringBuilder("\"");
        for (char value : text.toCharArray()) switch (value) {
            case '\\' -> result.append("\\\\"); case '"' -> result.append("\\\"");
            case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
            default -> { if (value < 32) result.append(String.format("\\u%04x", (int)value)); else result.append(value); }
        }
        return result.append('"').toString();
    }
    private static void writeProof(Path cell, List<Fetch> fetched, Set<Path> expected,
                                   boolean visible, boolean debug, boolean phb) throws Exception {
        long sourceFiles;
        try (var files = Files.walk(cell.resolve("runtime"))) {
            sourceFiles = files.filter(path -> path.toString().endsWith(".php")).count();
        }
        StringBuilder text = new StringBuilder("{\n\"debug\":" + debug + ",\"PHB\":" + phb + ",\"consumerVisible\":" + visible
                + ",\"runtimePhpSources\":" + sourceFiles + ",\n\"expected\":[");
        boolean comma = false;
        for (Path path : expected.stream().sorted().toList()) { if (comma) text.append(','); comma = true; text.append(json(path.toString())); }
        text.append("],\n\"fetches\":["); comma = false;
        for (Fetch fetch : fetched) {
            if (comma) text.append(','); comma = true;
            text.append("\n{\"path\":").append(json(fetch.request())).append(",\"sha256\":").append(json(fetch.sha256()))
                    .append(",\"compiledArtifact\":").append(fetch.compiled()).append(",\"compilerAbi\":").append(fetch.compilerAbi())
                    .append(",\"origin\":").append(json(fetch.origin())).append(",\"profile\":").append(json(fetch.profile()))
                    .append(",\"module\":").append(json(fetch.module())).append(",\"moduleContext\":").append(json(fetch.moduleContext())).append('}');
        }
        Files.writeString(cell.resolve("FETCH-PROOF.json"), text.append("\n]}\n").toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        if (phb) assertEquals("No SOURCE inputs appeared during PHB execution", 0, sourceFiles);
    }

    @Test(timeout=120000) public void warningPrecedesPhysicalTraitMethodCollisionAllFragmentsSourcePhb() throws Throwable {
        run(new Case("duplicate-method", "DepReviewConflictConsumer", List.of("duplicate-method/definitions.php", "duplicate-method/subject.php"), true));
    }
    @Test(timeout=120000) public void warningPrecedesPhysicalFinalParentFailureAllFragmentsSourcePhb() throws Throwable {
        run(new Case("invalid-parent", "DepReviewParentConsumer", List.of("invalid-parent/definitions.php", "invalid-parent/subject.php"), true));
    }
    @Test(timeout=120000) public void reservedNameRejectsPhysicalInnerDeclarationAfterInnerEnterAllFragmentsSourcePhb() throws Throwable {
        run(new Case("reentry-collision", "DepReviewCollisionConsumer", List.of("reentry-collision/definitions.php", "reentry-collision/subject.php", "reentry-collision/reentry-definition.php"), true));
    }
    static void standalone(String name) throws Throwable { run(new Case(name, null, List.of(), false)); }
}
