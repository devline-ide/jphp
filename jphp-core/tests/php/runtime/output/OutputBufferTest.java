package php.runtime.output;

import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.memory.BinaryMemory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class OutputBufferTest {
    @Test public void testHeadlessRootCallbackRetainsTransformedContents() throws Throwable {
        Environment env = new Environment(new CompileScope());
        try {
            env.getDefaultBuffer().setChunkSize(0);
            env.getDefaultBuffer().setCallback(env.eval("return function($text) { return '[' . $text . ']'; };"));
            env.echo("ab");
            env.getDefaultBuffer().flush();
            assertEquals("[ab]", env.getDefaultBuffer().getContents().toString());
            env.getDefaultBuffer().setCallback(null);
        } finally { env.doFinal(); }
    }

    @Test public void testHeadlessRootRetainsTextAndBinaryAfterFlushAndFinal() throws Throwable {
        Environment env = new Environment(new CompileScope());
        env.echo("prefix");
        byte[] binary = {(byte) 0xff, 0, (byte) 0xc3};
        env.echo(new BinaryMemory(binary));
        env.echo("x".repeat(5000)); // Exceeds the root chunk size.
        env.getDefaultBuffer().flush();
        env.doFinal();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write("prefix".getBytes(StandardCharsets.UTF_8));
        expected.write(binary);
        expected.write("x".repeat(5000).getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected.toByteArray(), env.getDefaultBuffer().getContents().getBinaryBytes(StandardCharsets.UTF_8));
    }

    @Test public void testNestedBuffersFeedHeadlessRoot() throws Throwable {
        Environment env = new Environment(new CompileScope());
        try {
            env.echo("a");
            env.pushOutputBuffer(null, 4, true);
            env.echo("bcdef");
            env.popOutputBuffer();
            assertEquals("abcdef", env.getDefaultBuffer().getContents().toString());
        } finally { env.doFinal(); }
    }

    @Test public void testChildEnvironmentHonorsExplicitOutput() throws Throwable {
        ByteArrayOutputStream parentOutput = new ByteArrayOutputStream(), childOutput = new ByteArrayOutputStream();
        Environment parent = new Environment(new CompileScope(), parentOutput);
        Environment child = new Environment(parent, childOutput);
        try { parent.echo("parent"); child.echo("child"); }
        finally { child.doFinal(); parent.doFinal(); }
        assertEquals("parent", parentOutput.toString(StandardCharsets.UTF_8));
        assertEquals("child", childOutput.toString(StandardCharsets.UTF_8));
    }
}
