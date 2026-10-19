package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.CoreExtension;
import php.runtime.memory.LongMemory;
import php.runtime.memory.helper.ConstantMemory;
import php.runtime.reflection.ParameterEntity;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

/** Prospective native symbolic-default controls; no fallback when actual resolution differs. */
public class PhpNativeDeclaredConstantTest {
    private interface Action {void run() throws Throwable;}
    private void illegal(Action action) throws Throwable {
        try {action.run();fail("Declaration mismatch was accepted");}
        catch (IllegalArgumentException expected) {}
    }

    @Test public void knownSymbolKeepsNonNullableMetadataAndActualResolution() throws Throwable {
        var scope = new CompileScope();scope.registerExtension(new CoreExtension());
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env = new Environment(scope, new ByteArrayOutputStream());
        try {
            var descriptor = new ConstantMemory("SORT_REGULAR", LongMemory.valueOf(0));
            var parameter = new ParameterEntity(null);parameter.setType(HintType.INT);
            parameter.setDefaultValue(descriptor);parameter.setDefaultValueConstName("SORT_REGULAR");
            assertFalse(parameter.isNullableOrDefaultNull());assertFalse(descriptor.isNull());
            assertEquals("SORT_REGULAR", parameter.getDefaultValueConstName());
            assertTrue(env.defineConstant("SORT_REGULAR", new LongMemory(0), true));
            assertSame(env.findConstant("SORT_REGULAR"), descriptor.toImmutable(env, TraceInfo.UNKNOWN));
            ((LongMemory) descriptor.getRawValue()).value = 1;
            illegal(() -> descriptor.toImmutable(env, TraceInfo.UNKNOWN));
        } finally {try {env.doFinal();} finally {scope.getClassLoader().close();}}
    }

    @Test public void missingOrWrongActualSymbolNeverReturnsTheDeclaredFallback() throws Throwable {
        var scope = new CompileScope();scope.registerExtension(new CoreExtension());
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env = new Environment(scope, new ByteArrayOutputStream());
        try {
            var descriptor = new ConstantMemory("SORT_REGULAR", LongMemory.valueOf(0));
            assertNull(env.findConstant("SORT_REGULAR"));
            boolean rejected = false;
            try {descriptor.toImmutable(env, TraceInfo.UNKNOWN);} catch (Throwable expected) {rejected = true;}
            assertTrue("Unresolved declaration returned a guessed fallback", rejected);
            assertTrue(env.defineConstant("SORT_REGULAR", LongMemory.valueOf(7), true));
            illegal(() -> descriptor.toImmutable(env, TraceInfo.UNKNOWN));
            illegal(() -> new ConstantMemory("UNDECLARED", LongMemory.valueOf(0)));
            illegal(() -> new ConstantMemory("SORT_REGULAR", Memory.NULL));
            illegal(() -> new ConstantMemory("SORT_REGULAR", LongMemory.valueOf(1)));
            assertTrue(new ConstantMemory("UNCHANGED_LEGACY_DEFAULT").isNull());
        } finally {try {env.doFinal();} finally {scope.getClassLoader().close();}}
    }
}
