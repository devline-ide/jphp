package php.runtime.output;

import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Contracts around exact-buffer internal dispatch; no private admission layout assumptions. */
public class PhpOutputExactDelegationTest {
    @Test public void exactRootAndChildPreserveTextBinaryAndFlushBytes() throws Throwable {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Environment env = new Environment(new CompileScope(), sink);
        try {
            OutputBuffer root = env.getDefaultBuffer();
            root.write("a\u20ac"); root.write(new byte[] {0, (byte) 255}); root.flush();
            OutputBuffer child = new OutputBuffer(env, root, null, 0); child.setLevel(1);
            child.write("b\u00e9"); child.write(new byte[] {1, (byte) 254}); child.flush(); root.flush();
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write("a\u20ac".getBytes(StandardCharsets.UTF_8)); expected.write(new byte[] {0, (byte) 255});
            expected.write("b\u00e9".getBytes(StandardCharsets.UTF_8)); expected.write(new byte[] {1, (byte) 254});
            assertArrayEquals(expected.toByteArray(), sink.toByteArray());
        } finally { env.doFinal(); }
    }

    private static class Hooked extends OutputBuffer {
        int single, ranged;
        final RuntimeException sentinel = new RuntimeException("protected-output-hook-sentinel");
        boolean throwing;
        Hooked(Environment env) { super(env, env.getDefaultBuffer(), null, 0); setLevel(1); }
        @Override protected void _write(byte[] bytes) throws Throwable {
            single++; if (throwing) throw sentinel; super._write(bytes);
        }
        @Override protected void _write(byte[] bytes, int length) throws Throwable {
            ranged++; super._write(bytes, length);
        }
    }
    @Test public void subclassRetainsBothProtectedWriteHooks() throws Throwable {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Environment env = new Environment(new CompileScope(), sink);
        try {
            Hooked child = new Hooked(env);
            child.write("str"); assertEquals(1, child.single); assertEquals(1, child.ranged);
            child.write(new byte[] {'b', 'i', 'n', 'x'}, 3);
            assertEquals(1, child.single); assertEquals(2, child.ranged);
            child.flush(); env.getDefaultBuffer().flush();
            assertArrayEquals("strbin".getBytes(StandardCharsets.UTF_8), sink.toByteArray());
        } finally { env.doFinal(); }
    }

    @Test public void protectedHookFailurePreservesIdentityAndReleasesAdmission() throws Throwable {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Environment env = new Environment(new CompileScope(), sink);
        try {
            Hooked child = new Hooked(env); child.throwing = true;
            try { child.write("lost"); fail("Protected hook must execute"); }
            catch (RuntimeException failure) { assertSame(child.sentinel, failure); }
            child.throwing = false; child.write("ok"); child.flush(); env.getDefaultBuffer().flush();
            assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), sink.toByteArray());
        } finally { env.doFinal(); }
    }

    @Test public void exactRootStillRejectsMutationInsideItsNativeSinkCallback() throws Throwable {
        final OutputBuffer[] root = new OutputBuffer[1];
        final int[] rejected = {0};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream sink = new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("Unexpected single-byte sink call"); }
            @Override public void write(byte[] value, int offset, int length) {
                bytes.write(value, offset, length);
                try { root[0].write("forbidden"); fail("Host sink callback mutation must reject"); }
                catch (IllegalStateException expected) { rejected[0]++; }
                catch (Throwable failure) { throw new AssertionError(failure); }
            }
        };
        Environment env = new Environment(new CompileScope(), sink);
        try {
            root[0] = env.getDefaultBuffer(); root[0].setImplicitFlush(true); root[0].write("ok");
            assertEquals(1, rejected[0]); assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), bytes.toByteArray());
        } finally { env.doFinal(); }
    }

    private static final class PublicHooked extends Hooked {
        int publicText, publicBytes, publicRange;
        PublicHooked(Environment env) { super(env); }
        @Override public void write(String text) throws Throwable { publicText++; super.write(text); }
        @Override public void write(byte[] bytes) throws Throwable { publicBytes++; super.write(bytes); }
        @Override public void write(byte[] bytes, int length) throws Throwable { publicRange++; super.write(bytes, length); }
    }
    @Test public void memoryDelegationPreservesPublicAndProtectedSubclassOverrides() throws Throwable {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Environment env = new Environment(new CompileScope(), sink);
        try {
            PublicHooked child = new PublicHooked(env);
            child.write(new php.runtime.memory.StringMemory("a\u20ac"));
            child.write(new php.runtime.memory.BinaryMemory(new byte[] {0, (byte) 255}));
            assertEquals(1, child.publicText); assertEquals(1, child.publicBytes); assertEquals(1, child.publicRange);
            assertEquals(1, child.single); assertEquals(2, child.ranged);
            child.flush(); env.getDefaultBuffer().flush();
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write("a\u20ac".getBytes(StandardCharsets.UTF_8)); expected.write(new byte[] {0, (byte) 255});
            assertArrayEquals(expected.toByteArray(), sink.toByteArray());
        } finally { env.doFinal(); }
    }

    @Test public void memoryConversionChangedLineageFallsBackBeforeRootOrChildWrites() throws Throwable {
        var foreign = new java.util.concurrent.atomic.AtomicReference<php.runtime.env.PhpExecutionLineage.Token>();
        Thread producer = Thread.ofPlatform().unstarted(() -> foreign.set(php.runtime.env.PhpExecutionLineage.current()));
        producer.start();
        try { producer.join(5000); }
        finally { if (producer.isAlive()) { producer.interrupt(); producer.join(5000); } }
        assertFalse("Owned token producer must terminate", producer.isAlive()); assertNotNull(foreign.get());
        for (boolean childMode : new boolean[] {false, true}) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Environment env = new Environment(new CompileScope(), sink);
            final php.runtime.env.PhpExecutionLineage.Binding[] changed = {null};
            try {
                OutputBuffer target = childMode ? new OutputBuffer(env, env.getDefaultBuffer(), null, 0) : env.getDefaultBuffer();
                if (childMode) target.setLevel(1);
                php.runtime.Memory value = new php.runtime.memory.StringMemory("lost") {
                    @Override public String toString() {
                        changed[0] = php.runtime.env.PhpExecutionLineage.bind(foreign.get());
                        return "lost";
                    }
                };
                try { target.write(value); fail("Changed lineage must not borrow the retained output scope"); }
                catch (php.runtime.env.PhpOperationGate.OverlapException expected) { }
                finally { if (changed[0] != null) { changed[0].close(); changed[0] = null; } }
                target.flush(); env.getDefaultBuffer().flush(); assertEquals(0, sink.size());
                target.write(new php.runtime.memory.StringMemory("ok")); target.flush(); env.getDefaultBuffer().flush();
                assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), sink.toByteArray());
            } finally {
                if (changed[0] != null) changed[0].close();
                env.doFinal();
            }
        }
    }
}
