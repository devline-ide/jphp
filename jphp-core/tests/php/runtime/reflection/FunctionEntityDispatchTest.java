package php.runtime.reflection;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.ReferenceMemory;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/** Passive dispatch must preserve manual bindings and the public exception transport. */
public class FunctionEntityDispatchTest {
    public static final class TransportEnvironment extends Environment {
        InvocationTargetException received;
        int transports;
        @Override public Memory __throwException(InvocationTargetException failure) {
            received = failure;
            transports++;
            return Memory.FALSE;
        }
    }
    public static final class Bodies {
        static Environment expectedEnvironment;
        static Memory[] expectedArguments;
        static Throwable failure;
        public static Memory exact(Environment env, Memory... arguments) {
            assertSame(expectedEnvironment, env);
            assertSame(expectedArguments, arguments);
            return arguments == null ? Memory.NULL : arguments[0];
        }
        public static Memory yes(Environment env, Memory[] arguments) { return Memory.TRUE; }
        public static Memory no(Environment env, Memory[] arguments) { return Memory.FALSE; }
        private static Memory hidden(Environment env, Memory[] arguments) { return Memory.TRUE; }
        public static Object objectResult(Environment env, Memory[] arguments) { return Memory.TRUE; }
        public static int primitiveResult(Environment env, Memory[] arguments) { return 7; }
        public static Memory wrongArity(Environment env) { return Memory.TRUE; }
        public static Memory narrowEnvironment(TransportEnvironment env, Memory[] arguments) { return Memory.TRUE; }
        public Memory instance(Environment env, Memory[] arguments) { return Memory.TRUE; }
        public static Memory fail(Environment env, Memory[] arguments) throws Throwable { throw failure; }
    }
    private static final class TrackedFunction extends FunctionEntity {
        int cleanups;
        TrackedFunction() { super(new Context(new File("dispatch.php"))); }
        @Override public void unsetArguments(Memory[] arguments) {
            cleanups++;
            super.unsetArguments(arguments);
        }
    }
    private static Method method(String name, Class<?>... parameters) throws Exception {
        return Bodies.class.getDeclaredMethod(name, parameters);
    }
    private static Method method(String name) throws Exception {
        return method(name, Environment.class, Memory[].class);
    }
    private static Memory invoke(TrackedFunction function, Environment env, Memory[] args) throws Throwable {
        return function.invoke(env, TraceInfo.UNKNOWN, args);
    }

    @Test public void exactVarargsBodyPreservesEnvironmentArrayAndReferenceIdentity() throws Throwable {
        Environment env = new Environment();
        TrackedFunction function = new TrackedFunction();
        function.setNativeMethod(method("exact"));
        ReferenceMemory reference = new ReferenceMemory(Memory.TRUE);
        Bodies.expectedEnvironment = env;
        Bodies.expectedArguments = new Memory[]{reference};
        assertSame(reference, invoke(function, env, Bodies.expectedArguments));
        Bodies.expectedArguments = null;
        assertSame(Memory.NULL, invoke(function, env, null));
        assertEquals(2, function.cleanups);
    }

    @Test public void rebindUsesTheNewMemberAndPrivateAccessibilityRemainsEffective() throws Throwable {
        Environment env = new Environment();
        TrackedFunction function = new TrackedFunction();
        function.setNativeMethod(method("yes"));
        assertSame(Memory.TRUE, invoke(function, env, new Memory[0]));
        function.setNativeMethod(method("no"));
        assertSame(Memory.FALSE, invoke(function, env, new Memory[0]));
        Method hidden = method("hidden");
        function.setNativeMethod(hidden);
        assertSame(hidden, function.getNativeMethod());
        assertSame(Memory.TRUE, invoke(function, env, new Memory[0]));
        hidden.setAccessible(false);
        try { invoke(function, env, new Memory[0]); fail("Private access must still be checked"); }
        catch (IllegalAccessException expected) { }
        function.setNativeMethod(method("yes"));
        assertSame(Memory.TRUE, invoke(function, env, new Memory[0]));
        assertEquals(5, function.cleanups);
    }

    @Test public void manualDescriptorsKeepReflectionConversionsAndInvocationFailures() throws Throwable {
        TransportEnvironment env = new TransportEnvironment();
        TrackedFunction function = new TrackedFunction();
        function.setNativeMethod(method("objectResult"));
        assertSame(Memory.TRUE, invoke(function, env, new Memory[0]));
        function.setNativeMethod(method("primitiveResult"));
        try { invoke(function, env, new Memory[0]); fail("Primitive return is not Memory"); }
        catch (ClassCastException expected) { }
        function.setNativeMethod(method("wrongArity", Environment.class));
        try { invoke(function, env, new Memory[0]); fail("Arity mismatch must not enter a body"); }
        catch (IllegalArgumentException expected) { }
        function.setNativeMethod(method("instance"));
        try { invoke(function, env, new Memory[0]); fail("Static dispatch has no instance receiver"); }
        catch (NullPointerException expected) { }
        function.setNativeMethod(method("narrowEnvironment", TransportEnvironment.class, Memory[].class));
        assertSame(Memory.TRUE, invoke(function, env, new Memory[0]));
        try { invoke(function, new Environment(), new Memory[0]); fail("Incompatible environment must be rejected"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(0, env.transports);
        assertEquals(6, function.cleanups);
    }

    @Test public void bodyFailuresReachCustomEnvironmentWithTheOriginalCauseAndCleanup() throws Throwable {
        TransportEnvironment env = new TransportEnvironment();
        TrackedFunction function = new TrackedFunction();
        function.setNativeMethod(method("fail"));
        for (Throwable primary : new Throwable[]{new IllegalStateException("primary"), new AssertionError("error")}) {
            Bodies.failure = primary;
            assertSame(Memory.FALSE, invoke(function, env, new Memory[0]));
            assertSame(primary, env.received.getTargetException());
        }
        assertEquals(2, env.transports);
        assertEquals(2, function.cleanups);
    }

    @Test public void thrownInvocationTargetExceptionKeepsItsOwnWrapperLayer() throws Throwable {
        TransportEnvironment env = new TransportEnvironment();
        TrackedFunction function = new TrackedFunction();
        function.setNativeMethod(method("fail"));
        Throwable cause = new IllegalArgumentException("inner");
        Bodies.failure = new InvocationTargetException(cause);
        assertSame(Memory.FALSE, invoke(function, env, new Memory[0]));
        assertNotSame(Bodies.failure, env.received);
        assertSame(Bodies.failure, env.received.getTargetException());
        assertSame(cause, ((InvocationTargetException)env.received.getTargetException()).getTargetException());
        assertEquals(1, function.cleanups);
    }

    @Test public void failedNullBindingClearsTheOldBodyAndStillRunsCleanup() throws Throwable {
        TrackedFunction function = new TrackedFunction();
        function.setNativeMethod(method("yes"));
        try { function.setNativeMethod(null); fail("Null binding must fail"); }
        catch (NullPointerException expected) { }
        assertNull(function.getNativeMethod());
        try { invoke(function, new Environment(), new Memory[0]); fail("Old body must not survive failed null binding"); }
        catch (NullPointerException expected) { }
        assertEquals(1, function.cleanups);
    }
}
