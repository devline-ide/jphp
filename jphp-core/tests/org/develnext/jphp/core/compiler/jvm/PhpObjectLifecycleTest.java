package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.classes.WrapInvoker;
import php.runtime.invoke.Invoker;
import php.runtime.invoke.PhpObjectLifecycle;
import php.runtime.lang.BaseException;
import php.runtime.lang.BaseObject;
import php.runtime.lang.IObject;
import php.runtime.lang.support.IManualDestructable;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import php.runtime.exceptions.CriticalException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Existing PHP dispatch paths and explicit Domain callers; no compiler ownership accounting activation. */
public class PhpObjectLifecycleTest extends JvmCompilerCase {
    @FunctionalInterface private interface Work {void run() throws Throwable;}
    @FunctionalInterface private interface Body {Memory run(Memory[] args) throws Throwable;}
    private String resource(String name) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/" + name)) {
            assertNotNull(input);return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private ModuleEntity compile(Environment env, String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        var context = new Context(source.startsWith("<?php") ? source.substring(5) : source);context.setPhpLanguageProfile(profile);
        var module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            var dumper = new ModuleDumper(context, env, true);var bytes = new ByteArrayOutputStream();dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private Memory callback(Environment env, Body body) {
        return new ObjectMemory(new WrapInvoker(env, new Invoker(env, TraceInfo.UNKNOWN) {
            public ParameterEntity[] getParameters() {return new ParameterEntity[0];}
            public String getName() {return "lifecycle_callback";}
            public int getArgumentCount() {return 0;}
            protected void pushCall(TraceInfo trace, Memory[] args) {}
            protected Memory invoke(Memory... args) throws Throwable {return body.run(args);}
            public int canAccess(Environment target) {return 0;}
        }));
    }
    private void identity(Throwable expected, Work work) throws Throwable {
        try {work.run();fail("Expected original failure");} catch (Throwable actual) {assertSame(expected, actual);}
    }
    @Test public void actualPhpManualReentryAndShutdownAreOneShotSourceAndDump() throws Throwable {
        for (boolean shutdown : new boolean[]{false, true}) for (var profile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);var output = new ByteArrayOutputStream();var env = new Environment(scope, output);
            try {
                var value = compile(env, resource("php85_object_lifecycle.php"), profile, dumped).include(env).toValue(ObjectMemory.class);
                int depth = env.getCallStack().getTop();int[] entries = {0};
                value.value.setProp("callback", callback(env, args -> {
                    assertSame(value.value, args[0].toObject(IObject.class));assertTrue(value.value.isFinalized());entries[0]++;
                    value.manualUnset(env);env.finalizeObjects();return Memory.NULL;
                }));
                if (shutdown) env.finalizeObjects();else value.manualUnset(env);
                value.manualUnset(env);env.finalizeObjects();
                assertEquals(1, entries[0]);assertEquals(1, value.value.getProp("calls").toLong());assertEquals(depth, env.getCallStack().getTop());
                env.doFinal();assertEquals("destroy:1\n", output.toString(StandardCharsets.UTF_8));
            } finally {env.doFinal();}
        }
    }
    @Test public void actualPhpThrowingDestructorPreservesIdentityAndFinalizedBitSourceAndDump() throws Throwable {
        for (boolean shutdown : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);var output = new ByteArrayOutputStream();var env = new Environment(scope, output);
            try {
                var value = compile(env, resource("php85_object_lifecycle.php"), PhpLanguageProfile.PHP_8_5, dumped).include(env).toValue(ObjectMemory.class);
                var failure = new BaseException(env);failure.__construct(env, php.runtime.memory.StringMemory.valueOf("destructor"));
                value.value.setProp("failure", new ObjectMemory(failure));int depth = env.getCallStack().getTop();
                identity(failure, () -> {if (shutdown) env.finalizeObjects();else value.manualUnset(env);});
                assertTrue(value.value.isFinalized());assertEquals(depth, env.getCallStack().getTop());
                value.manualUnset(env);env.finalizeObjects();env.doFinal();
                assertEquals(1, value.value.getProp("calls").toLong());assertEquals("destroy:1\n", output.toString(StandardCharsets.UTF_8));
            } finally {env.doFinal();}
        }
    }
    @Test public void actualPhpDestructorUsesReleasingEnvironmentNotAllocationEnvironmentSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);var firstOutput = new ByteArrayOutputStream();var secondOutput = new ByteArrayOutputStream();
            var first = new Environment(scope, firstOutput);Environment second = null;
            try {
                var module = compile(first, resource("php85_object_lifecycle.php"), PhpLanguageProfile.PHP_8_5, dumped);
                var value = module.include(first).toValue(ObjectMemory.class);second = new Environment(scope, secondOutput);second.registerModule(module);
                assertSame(first, value.value.getEnvironment());second.registerObjectInGC(value.value);value.manualUnset(second);
                assertEquals(1, value.value.getProp("calls").toLong());second.doFinal();first.doFinal();
                assertEquals("", firstOutput.toString(StandardCharsets.UTF_8));assertEquals("destroy:1\n", secondOutput.toString(StandardCharsets.UTF_8));
            } finally {if (second != null) second.doFinal();first.doFinal();}
        }
    }
    @Test public void explicitDomainLifecycleDispatchesActualPhpAndPreservesPrimaryFailureSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);var output = new ByteArrayOutputStream();var env = new Environment(scope, output);
            var domain = new PhpOwnershipDomain();var participant = domain.participant(env);
            try {
                var value = compile(env, resource("php85_object_lifecycle.php"), PhpLanguageProfile.PHP_8_5, dumped).include(env).toValue(ObjectMemory.class);
                var failure = new BaseException(env);failure.__construct(env, php.runtime.memory.StringMemory.valueOf("destructor"));
                value.value.setProp("failure", new ObjectMemory(failure));
                try (var ignored = participant.enter()) {
                    var node = domain.object(value.value, new PhpOwnershipDomain.Lifecycle() {
                        public void destruct(PhpOwnership.Node target, Object cleanupContext) throws Throwable {
                            PhpObjectLifecycle.destruct((IObject)target.identity(), (Environment)cleanupContext, PhpObjectLifecycle.Reason.OWNERSHIP_RELEASE);
                        }
                    });
                    PhpOwnershipValues.bindValue(domain, value, node).own(participant);
                }
                var primary = new Exception("pending");identity(primary, () -> participant.unwind(primary));
                assertArrayEquals(new Throwable[]{failure}, primary.getSuppressed());assertEquals(0, domain.graph().liveNodes());assertTrue(value.value.isFinalized());
                env.doFinal();assertEquals("destroy:1\n", output.toString(StandardCharsets.UTF_8));
            } finally {participant.close();env.doFinal();}
        }
    }
    @Test public void nativeResurrectionAndThrowingOneShotControlsSourceAndDump() throws Throwable {
        String source = resource("php85_destructor_once.php"), expected = resource("php85_destructor_once.expected");
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var output = new ByteArrayOutputStream();var env = new Environment(scope, output);
            try {compile(env, source, PhpLanguageProfile.PHP_8_5, dumped).include(env);env.doFinal();assertEquals(expected, output.toString(StandardCharsets.UTF_8));}
            finally {env.doFinal();}
        }
    }
    private static final class ManualProbe extends BaseObject implements IManualDestructable {
        private Throwable hookFailure;
        private int hooks;
        private boolean failBeforeDispatch;
        ManualProbe(Environment env, ClassEntity clazz) {super(env, clazz);}
        @Override public boolean isFinalized() {if (failBeforeDispatch) throw (Error)hookFailure;return super.isFinalized();}
        public void onManualDestruct(Environment env) {hooks++;if (hookFailure != null) PhpObjectLifecycleTest.<RuntimeException>raise(hookFailure);}
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void raise(Throwable failure) throws T {throw (T)failure;}
    @Test public void manualHookAndFailureConversionBoundariesRemainExact() throws Throwable {
        var env = new Environment(newScope(), new ByteArrayOutputStream());
        try {
            for (var reason : PhpObjectLifecycle.Reason.values()) {
                int[] calls = {0};var clazz = new ClassEntity(new Context(""));clazz.setName("ManualProbe");
                var method = new MethodEntity(new Context("")) {
                    public Memory invokeDynamic(IObject object, Environment releasing, TraceInfo trace, Memory... args) {
                        assertSame(env, releasing);if (reason == PhpObjectLifecycle.Reason.ENVIRONMENT_FINALIZE) assertSame(TraceInfo.UNKNOWN, trace);
                        calls[0]++;return Memory.NULL;
                    }
                };method.setName("__destruct");method.setClazz(clazz);clazz.methodDestruct = method;
                var probe = new ManualProbe(env, clazz);assertTrue(PhpObjectLifecycle.destruct(probe, env, reason));
                assertFalse(PhpObjectLifecycle.destruct(probe, env, reason));assertEquals(1, calls[0]);
                assertEquals(reason == PhpObjectLifecycle.Reason.ENVIRONMENT_FINALIZE ? 0 : 1, probe.hooks);
                var raw = new AssertionError("hook");probe = new ManualProbe(env, clazz);probe.hookFailure = raw;int depth = env.getCallStack().getTop();
                if (reason == PhpObjectLifecycle.Reason.MANUAL_UNSET) {
                    try {PhpObjectLifecycle.destruct(probe, env, reason);fail();} catch (CriticalException actual) {assertSame(raw, actual.getCause());}
                } else if (reason == PhpObjectLifecycle.Reason.OWNERSHIP_RELEASE) {
                    ManualProbe exact = probe;identity(raw, () -> PhpObjectLifecycle.destruct(exact, env, reason));
                } else {assertTrue(PhpObjectLifecycle.destruct(probe, env, reason));}
                assertEquals(depth, env.getCallStack().getTop());assertTrue(probe.isFinalized());
                var precheck = new ManualProbe(env, clazz);precheck.hookFailure = raw;precheck.failBeforeDispatch = true;
                identity(raw, () -> new ObjectMemory(precheck).manualUnset(env));assertEquals(depth, env.getCallStack().getTop());
            }
        } finally {env.doFinal();}
    }
}
