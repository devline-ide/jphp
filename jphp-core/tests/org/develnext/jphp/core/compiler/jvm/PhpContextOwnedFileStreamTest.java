package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.lang.ref.Reference;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** Descriptor ownership, using real files and immediate rename/delete without GC or retries. */
public class PhpContextOwnedFileStreamTest {
    private static final byte[] BAD_PHB = new byte[] {0, 1, 2, 3, 4};
    private static final class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory(Path.of(System.getProperty("qa.scratch", System.getProperty("java.io.tmpdir"))), "jphp-context-owned-");
        final Path file = directory.resolve("input.phb"), moved = directory.resolve("renamed.phb");
        Fixture(byte[] bytes) throws IOException { Files.write(file, bytes); }
        void renameAndDelete() throws IOException {
            Files.move(file, moved); assertFalse(Files.exists(file));
            Files.delete(moved); assertFalse(Files.exists(moved));
        }
        @Override public void close() throws IOException {
            Files.deleteIfExists(file); Files.deleteIfExists(moved); Files.delete(directory);
        }
    }
    private static final class TrackingInput extends ByteArrayInputStream {
        boolean closed;
        TrackingInput(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
    private static Environment env() { return new Environment(new CompileScope(), new ByteArrayOutputStream()); }
    private static byte[] compiledFixture() throws Throwable {
        Environment compiler = env(); Context context = new Context("return 17;");
        try {
            ModuleEntity module = new JvmCompiler(compiler, context).compile(false);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new ModuleDumper(context, compiler, true).save(module, bytes);
            return bytes.toByteArray();
        } finally { compiler.doFinal(); }
    }
    private static void invalidImport(Environment env, Context context) throws Throwable {
        try { env.importCompiledModule(context, true); fail("Malformed PHB must fail"); }
        catch (IOException expected) { }
    }

    @Test public void ownedFileContentReadClosesBeforeImmediateRenameAndDelete() throws Exception {
        try (Fixture fixture = new Fixture("alpha\nbeta".getBytes(StandardCharsets.UTF_8))) {
            Context context = new Context(fixture.file.toFile());
            assertEquals("alpha\nbeta\n", context.getContent());
            fixture.renameAndDelete(); assertEquals("alpha\nbeta\n", context.getContent());
            Reference.reachabilityFence(context);
        }
    }
    @Test public void ownedFileContentFailureClosesAndPreservesTheOriginalException() throws Exception {
        IOException sentinel = new IOException("read-content-sentinel"); Reader[] observed = new Reader[1];
        try (Fixture fixture = new Fixture(new byte[] {'x'})) {
            Context context = new Context(fixture.file.toFile()) {
                @Override protected void readContent(Reader reader) throws IOException { observed[0] = reader; throw sentinel; }
            };
            try { context.getContent(); fail("Reader callback must throw"); } catch (IOException failure) { assertSame(sentinel, failure); }
            fixture.renameAndDelete();
            try { observed[0].read(); fail("The internally opened file descriptor must be closed after failure"); }
            catch (IOException expected) { }
            Reference.reachabilityFence(context); Reference.reachabilityFence(observed);
        }
    }
    @Test public void ownedCompiledFileImportClosesBeforeRenameDeleteAndLaterExecution() throws Throwable {
        try (Fixture fixture = new Fixture(compiledFixture())) {
            Environment env = env(); Context context = new Context(fixture.file.toFile());
            try {
                ModuleEntity module = env.importCompiledModule(context, true);
                fixture.renameAndDelete(); assertEquals(17, module.include(env).toLong());
                Reference.reachabilityFence(module); Reference.reachabilityFence(context);
            } finally { env.doFinal(); }
        }
    }
    @Test public void malformedOwnedCompiledFileClosesBeforeRenameDeleteWithoutPublication() throws Throwable {
        try (Fixture fixture = new Fixture(BAD_PHB)) {
            Environment env = env(); Context context = new Context(fixture.file.toFile());
            try {
                invalidImport(env, context);
                assertNull(env.scope.findCachedModule(context.getModuleName(), true, context.getPhpLanguageProfile(env.scope)));
                fixture.renameAndDelete(); Reference.reachabilityFence(context);
            } finally { env.doFinal(); }
        }
    }
    @Test public void borrowedContentStreamsStayOpenOnSuccessAndReaderFailure() throws Exception {
        TrackingInput input = new TrackingInput("borrowed".getBytes(StandardCharsets.UTF_8));
        try { assertEquals("borrowed\n", new Context(input).getContent()); assertFalse(input.closed); }
        finally { input.close(); }
        IOException sentinel = new IOException("borrowed-read-sentinel"); boolean[] closed = {false};
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw sentinel; }
            @Override public void close() { closed[0] = true; }
        };
        try {
            try { new Context(failing).getContent(); fail("Borrowed read must throw"); }
            catch (IOException failure) { assertSame(sentinel, failure); }
            assertFalse(closed[0]);
        } finally { failing.close(); }
    }
    @Test public void borrowedCompiledStreamsStayOpenOnSuccessAndMalformedInput() throws Throwable {
        for (boolean valid : new boolean[] {true, false}) {
            Environment env = env(); TrackingInput input = new TrackingInput(valid ? compiledFixture() : BAD_PHB);
            Context context = new Context(input);
            try {
                if (valid) assertEquals(17, env.importCompiledModule(context, true).include(env).toLong());
                else invalidImport(env, context);
                assertFalse("Environment must not close a borrowed input", input.closed);
                assertSame(input, context.getInputStream(StandardCharsets.UTF_8));
            } finally { input.close(); env.doFinal(); }
        }
    }
    @Test public void customFileContextKeepsVirtualBorrowedStreamContractOnSuccessAndFailure() throws Throwable {
        for (boolean valid : new boolean[] {true, false}) {
            try (Fixture fixture = new Fixture(new byte[0])) {
                Environment env = env(); TrackingInput input = new TrackingInput(valid ? compiledFixture() : BAD_PHB);
                int[] calls = {0};
                Context context = new Context(fixture.file.toFile()) {
                    @Override public InputStream getInputStream(Charset charset) { calls[0]++; return input; }
                };
                try {
                    if (valid) assertEquals(17, env.importCompiledModule(context, true).include(env).toLong());
                    else invalidImport(env, context);
                    assertEquals(1, calls[0]); assertFalse("Custom Context retains ownership of its returned input", input.closed);
                    fixture.renameAndDelete(); Reference.reachabilityFence(context);
                } finally { input.close(); env.doFinal(); }
            }
        }
    }
    @Test public void publicFileInputStreamRemainsAnExplicitCallerOwnedOpenHandle() throws Exception {
        try (Fixture fixture = new Fixture(new byte[] {'x'})) {
            Context context = new Context(fixture.file.toFile());
            try (InputStream callerOwned = context.getInputStream(StandardCharsets.UTF_8)) {
                assertEquals("x\n", context.getContent()); assertEquals('x', callerOwned.read());
            }
            fixture.renameAndDelete(); Reference.reachabilityFence(context);
        }
    }
}
