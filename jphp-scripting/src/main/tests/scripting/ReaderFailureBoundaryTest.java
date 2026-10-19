package scripting;

import org.develnext.jphp.scripting.JPHPScriptEngine;
import org.develnext.jphp.scripting.util.ReaderInputStream;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import javax.script.ScriptException;
import java.io.*;
import static org.junit.Assert.*;

/** Recovery uses only this invocation's actual adapter failure, never a rendered PHP exception. */
public class ReaderFailureBoundaryTest {
    @Test public void engineUncheckedReaderFailureRetainsExactCauseAndSuppressedCleanup() throws Exception {
        for (boolean modern : new boolean[]{false, true}) for (boolean error : new boolean[]{false, true}) {
            Throwable primary = error ? new AssertionError("actual Reader Error") : new IllegalStateException("actual Reader runtime");
            IOException cleanup = new IOException("actual cleanup");
            int[] closes = {0};
            Reader input = new Reader() {
                public int read(char[] b, int off, int len) {
                    if (primary instanceof Error) throw (Error) primary;
                    throw (RuntimeException) primary;
                }
                public void close() throws IOException {closes[0]++; throw cleanup;}
            };
            var engine = new JPHPScriptEngine();
            var field = JPHPScriptEngine.class.getDeclaredField("environment");field.setAccessible(true);
            ((Environment) field.get(engine)).getScope().setPhpLanguageProfile(modern ? PhpLanguageProfile.PHP_8_5 : PhpLanguageProfile.JPHP_LEGACY);
            ScriptException failure = assertThrows(ScriptException.class, () -> engine.compile(input));
            assertSame(primary, failure.getCause());
            assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
            assertEquals(1, closes[0]);
            assertEquals("7", engine.compile("<?php return 7;").eval().toString());
        }
    }

    @Test public void onlyPullFailureIsAvailableForEngineRecovery() throws Exception {
        IOException cleanup = new IOException("explicit close");
        var closeOnly = new ReaderInputStream(new Reader() {
            public int read(char[] b, int off, int len) {return -1;}
            public void close() throws IOException {throw cleanup;}
        }, "UTF-8");
        assertNull(closeOnly.getReadFailure());
        assertSame(cleanup, assertThrows(IOException.class, closeOnly::close));
        assertNull("Explicit cleanup must not replace another compile primary", closeOnly.getReadFailure());
        closeOnly.close();
        IOException pull = new IOException("actual pull");
        var failed = new ReaderInputStream(new Reader() {
            public int read(char[] b, int off, int len) throws IOException {throw pull;}
            public void close() {}
        }, "UTF-8");
        assertSame(pull, assertThrows(IOException.class, failed::read));
        assertSame(pull, failed.getReadFailure());
        failed.close();
        try (var success = new ReaderInputStream(new StringReader("x"), "UTF-8")) {
            assertEquals('x', success.read());assertEquals(-1, success.read());assertNull(success.getReadFailure());
        }
    }
}
