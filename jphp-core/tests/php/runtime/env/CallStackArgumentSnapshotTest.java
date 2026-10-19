package php.runtime.env;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.ext.CoreExtension;
import php.runtime.lang.exception.BaseError;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Permanent vector-lifetime controls; Memory values/cells are not copied or coerced. */
public class CallStackArgumentSnapshotTest {
    private Environment environment(PhpLanguageProfile profile) {
        var scope = new CompileScope();scope.registerExtension(new CoreExtension());
        scope.setPhpLanguageProfile(profile);
        return new Environment(scope, new ByteArrayOutputStream());
    }
    private void close(Environment env) throws Throwable {
        try {env.doFinal();} finally {env.getScope().getClassLoader().close();}
    }

    @Test public void capturedArgumentsSurviveCallBufferClearAndLazyErrorProperties() throws Throwable {
        for (var profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5}) {
            var env = environment(profile);
            try {
                var first = new LongMemory(3);var second = StringMemory.valueOf("argument");
                Memory[] raw = {first, second};var trace = new TraceInfo("snapshot.php", 0, 0);
                env.pushCall(trace, null, raw, "callback", null, null);
                assertSame(raw, env.peekCall(0).args);
                var snapshots = env.getCallStackSnapshot();var captured = snapshots[snapshots.length - 1];
                assertNotSame(raw, captured.args);
                assertSame(first, captured.args[0]);assertSame(second, captured.args[1]);
                var failure = new BaseError(env);failure.__construct(env, StringMemory.valueOf("stop"));
                failure.setTraceInfo(env, trace);
                env.popCall();Arrays.fill(raw, null);
                assertEquals(3, captured.args[0].toLong());assertEquals("argument", captured.args[1].toString());
                var copiedArgs = captured.toArray(false, false).valueOfIndex("args").toValue(ArrayMemory.class);
                assertEquals(3, copiedArgs.valueOfIndex(0).toLong());
                assertEquals("argument", copiedArgs.valueOfIndex(1).toString());
                assertEquals("stop", failure.getMessage(env).toString());
                var savedTrace = failure.getTrace(env).toValue(ArrayMemory.class);
                var savedArgs = savedTrace.valueOfIndex(savedTrace.size() - 1).toValue(ArrayMemory.class)
                        .valueOfIndex("args").toValue(ArrayMemory.class);
                assertEquals(3, savedArgs.valueOfIndex(0).toLong());
                assertEquals("argument", savedArgs.valueOfIndex(1).toString());
            } finally {close(env);}
        }
    }

    @Test public void snapshotKeepsTheSameReferenceElementsAndNullArgumentVector() throws Throwable {
        for (var profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5}) {
            var env = environment(profile);
            try {
                var reference = new ReferenceMemory(LongMemory.valueOf(1));Memory[] raw = {reference};
                env.pushCall(TraceInfo.UNKNOWN, null, raw, "reference", null, null);
                var snapshots = env.getCallStackSnapshot();var captured = snapshots[snapshots.length - 1];
                assertNotSame(raw, captured.args);assertSame(reference, captured.args[0]);
                reference.assign(7);raw[0] = LongMemory.valueOf(99);Arrays.fill(raw, null);env.popCall();
                assertSame(reference, captured.args[0]);assertEquals(7, captured.args[0].toLong());
                env.pushCall(TraceInfo.UNKNOWN, null, null, "no_arguments", null, null);
                snapshots = env.getCallStackSnapshot();captured = snapshots[snapshots.length - 1];
                assertNull(captured.args);
                assertEquals(0, captured.toArray(false, false).valueOfIndex("args").toValue(ArrayMemory.class).size());
                env.popCall();
            } finally {close(env);}
        }
    }
}
