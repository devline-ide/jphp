package php.runtime.invoke;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.common.HintType;
import php.runtime.env.*;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.CompileFunctionEntity;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

/** Prospective exact dispatcher-tail scope negatives; no argument-value name wrappers. */
public class BoundNativeVariadicScopeTest {
    public static class Provider {
        @Reflection.Signature(namedVariadic=true,value={@Reflection.Arg(value="head",type=HintType.MIXED),
                @Reflection.Arg(value="args",type=HintType.MIXED,variadic=true)})
        public static Memory invoke(Environment env,TraceInfo trace,Memory head,Memory... args) {return Memory.NULL;}
        public static Memory other(Memory[] args) {return Memory.NULL;}
    }
    private CompileFunctionEntity target() throws Exception {
        var spec=new CompileFunctionSpec("bound_native_fixture");
        spec.addMethod(Provider.class.getDeclaredMethod("invoke",Environment.class,TraceInfo.class,Memory.class,Memory[].class));
        return new CompileFunctionEntity(null,spec);
    }
    private BoundArguments bound(Object owner,Environment env,CompileFunctionEntity target) {
        var signature=new PhpArgumentBinder.Signature(target,target.getParameters(),true,false);
        var binder=new PhpArgumentBinder(owner,env,TraceInfo.UNKNOWN,signature,target.getName());
        binder.prepareNamed("named");binder.send(LongMemory.valueOf(3));
        binder.prepareNamed("head");binder.send(LongMemory.valueOf(1));
        return binder.finish();
    }
    @Test public void exactTailMemberEnvironmentNestedAndExpiredScope() throws Throwable {
        var env=new Environment(new CompileScope(),new ByteArrayOutputStream());
        var otherEnv=new Environment(new CompileScope(),new ByteArrayOutputStream());
        var target=target();var member=target.getCompileFunction().find(1).method;
        var owner=new Object();var bound=bound(owner,env,target);var tail=new Memory[0];
        try {
            try (var scope=bound.enter(owner,env)) {
                var vector=InvokeArgumentHelper.makeBoundNativeArguments(env,TraceInfo.UNKNOWN,bound);
                assertEquals(1,vector.length);assertEquals(3,bound.namedExtras().get("named").toLong());
                try {
                    BoundArguments.enterNativeVariadic(env,vector,target,member,new Memory[]{Memory.NULL});
                    fail("wrong physical tail length accepted");
                } catch (IllegalArgumentException expected) {}
                assertNull(BoundArguments.enterNativeVariadic(otherEnv,vector,target,member,tail));
                assertNull(BoundArguments.enterNativeVariadic(env,vector.clone(),target,member,tail));
                assertNull(BoundArguments.enterNativeVariadic(env,vector,target(),member,tail));
                try {
                    BoundArguments.enterNativeVariadic(env,vector,target,Provider.class.getDeclaredMethod("other",Memory[].class),tail);
                    fail("foreign selected member accepted");
                } catch (IllegalArgumentException expected) {}
                try (var nativeScope=BoundArguments.enterNativeVariadic(env,vector,target,member,tail)) {
                    assertNotNull(nativeScope);
                    var threadFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                    var foreignThread=new Thread(() -> {
                        try {
                            assertNull(BoundArguments.currentNativeVariadic(env,tail,member));
                            assertNull(BoundArguments.enterNativeVariadic(env,vector,target,member,tail));
                        } catch (Throwable failure) {threadFailure.set(failure);}
                    },"bound-native-scope-negative");
                    foreignThread.start();foreignThread.join(2000);
                    assertFalse("foreign-thread control did not finish",foreignThread.isAlive());
                    if (threadFailure.get()!=null) throw new AssertionError("foreign thread acquired scope",threadFailure.get());
                    assertSame(bound,BoundArguments.currentNativeVariadic(env,tail,
                            Provider.class.getDeclaredMethod("invoke",Environment.class,TraceInfo.class,Memory.class,Memory[].class)));
                    assertNull(BoundArguments.currentNativeVariadic(env,tail.clone(),member));
                    assertNull(BoundArguments.currentNativeVariadic(otherEnv,tail,member));
                    assertNull(BoundArguments.currentNativeVariadic(env,tail,Provider.class.getDeclaredMethod("other",Memory[].class)));
                    try {BoundArguments.enterNativeVariadic(env,vector,target,member,tail.clone());fail("second mint accepted");}
                    catch (IllegalStateException expected) {}
                    try {scope.close();fail("outer scope closed under native body");}
                    catch (IllegalStateException expected) {}
                    var childOwner=new Object();var child=bound(childOwner,env,target);var childTail=new Memory[0];
                    try {
                        try (var childScope=child.enter(childOwner,env)) {
                            var childVector=InvokeArgumentHelper.makeBoundNativeArguments(env,TraceInfo.UNKNOWN,child);
                            assertNull(BoundArguments.currentNativeVariadic(env,tail,member));
                            try (var childNative=BoundArguments.enterNativeVariadic(env,childVector,target,member,childTail)) {
                                assertSame(child,BoundArguments.currentNativeVariadic(env,childTail,member));
                                assertNull(BoundArguments.currentNativeVariadic(env,tail,member));
                            }
                        }
                    } finally {child.release();}
                    assertSame(bound,BoundArguments.currentNativeVariadic(env,tail,member));
                }
                assertNull(BoundArguments.currentNativeVariadic(env,tail,member));
                try {BoundArguments.enterNativeVariadic(env,vector,target,member,tail);fail("expired invocation re-minted");}
                catch (IllegalStateException expected) {}
            }
            assertNull(BoundArguments.currentNativeVariadic(env,tail,member));
        } finally {bound.release();try {env.doFinal();} finally {env.getScope().getClassLoader().close();}
            try {otherEnv.doFinal();} finally {otherEnv.getScope().getClassLoader().close();}}
    }
    @Test public void bodyFailureClosesActualTailScopeBeforeCallScope() throws Throwable {
        var env=new Environment(new CompileScope(),new ByteArrayOutputStream());var target=target();
        var member=target.getCompileFunction().find(1).method;var owner=new Object();var bound=bound(owner,env,target);
        var tail=new Memory[0];var primary=new IllegalArgumentException("body");
        try {
            try (var scope=bound.enter(owner,env)) {
                var vector=InvokeArgumentHelper.makeBoundNativeArguments(env,TraceInfo.UNKNOWN,bound);
                try (var nativeScope=BoundArguments.enterNativeVariadic(env,vector,target,member,tail)) {throw primary;}
                catch (IllegalArgumentException failure) {assertSame(primary,failure);}
                assertNull(BoundArguments.currentNativeVariadic(env,tail,member));
            }
            assertNull(BoundArguments.current(env,bound.arguments(),target));
        } finally {bound.release();try {env.doFinal();} finally {env.getScope().getClassLoader().close();}}
    }
}
