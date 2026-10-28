package php.runtime.invoke;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.Closure;
import php.runtime.reflection.helper.ClosureEntity;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;

public class ClosureDisplayNameTest {
    private static Closure closure(ClosureEntity declaration) {
        return new Closure(null, declaration) {
            @Override public Memory __invoke(Environment env, Memory... args) { return Memory.NULL; }
            @Override public Memory getOrCreateStatic(String name) { return getOrCreateStatic(name, Memory.NULL); }
        };
    }

    @Test public void currentDeclarationTraceAndUnknownLocationsRemainAuthoritative() {
        var declaration = new ClosureEntity(new Context(""));
        var closure = closure(declaration);
        declaration.setTrace(null);
        assertEquals("{closure}", InvokeArgumentHelper.closureCallName(closure));
        declaration.setTrace(TraceInfo.UNKNOWN);
        assertEquals("{closure}", InvokeArgumentHelper.closureCallName(closure));
        declaration.setTrace(TraceInfo.valueOf("source.php", 3L, 0L, "first-module"));
        assertEquals("{closure:source.php:4}", InvokeArgumentHelper.closureCallName(closure));
        declaration.setTrace(TraceInfo.valueOf("source.php", 3L, 0L, "another-module"));
        assertEquals("{closure:source.php:4}", InvokeArgumentHelper.closureCallName(closure));
        declaration.setTrace(new TraceInfo("другая/😀.php", 9, 0));
        assertEquals("{closure:другая/😀.php:10}", InvokeArgumentHelper.closureCallName(closure));
        declaration.setTrace(new TraceInfo("source.php", 4, 0));
        assertEquals("{closure:source.php:5}", InvokeArgumentHelper.closureCallName(closure));
        String oversized = "x".repeat(2048) + ".php";
        declaration.setTrace(new TraceInfo(oversized, 0, 0));
        assertEquals("{closure:" + oversized + ":1}", InvokeArgumentHelper.closureCallName(closure));
    }

    @Test public void collidingPathsAndConcurrentDeclarationsNeverShareTheWrongDisplay() throws Exception {
        assertEquals("Aa.php".hashCode(), "BB.php".hashCode());
        var executor = Executors.newFixedThreadPool(4);
        try {
            var work = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                final int offset = worker;
                work.add(() -> {
                    var declaration = new ClosureEntity(new Context(""));
                    var closure = closure(declaration);
                    for (int i = 0; i < 1024; i++) {
                        String file = ((i + offset) & 1) == 0 ? "Aa.php" : "BB.php";
                        int line = i & 7;
                        declaration.setTrace(new TraceInfo(file, line, 0));
                        assertEquals("{closure:" + file + ":" + (line + 1) + "}", InvokeArgumentHelper.closureCallName(closure));
                    }
                    return null;
                });
            }
            for (var result : executor.invokeAll(work)) result.get();
        } finally { executor.shutdownNow(); }
    }
}
