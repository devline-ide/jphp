package php.runtime.env;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real Environment admission/retirement controls, including physical-gate-free GUI park. */
public class PhpEnvironmentRetirementProvenanceTest {
    private interface Action {void run()throws Throwable;}
    private static Thread start(String label,Action action,AtomicReference<Throwable> failure) {
        Thread t=new Thread(()->{try{action.run();}catch(Throwable problem){failure.compareAndSet(null,problem);}},label);t.setDaemon(true);t.start();return t;
    }
    private static void await(CountDownLatch latch)throws InterruptedException {assertTrue(latch.await(5,TimeUnit.SECONDS));}
    private static void join(Thread thread)throws InterruptedException {thread.join(5000);assertFalse("Owned fixture thread remains live",thread.isAlive());}
    private static Environment env(CompileScope scope){return new Environment(scope,new ByteArrayOutputStream());}
    private static boolean flag(Environment env,String name)throws Exception {Field f=Environment.class.getDeclaredField(name);f.setAccessible(true);return f.getBoolean(env);}
    private static void waiting(Thread thread)throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(thread.getState()!=Thread.State.WAITING&&thread.isAlive()&&System.nanoTime()<limit)Thread.sleep(1);
        assertTrue(thread.isAlive());assertEquals("Real waiting admission, before finalization effects",Thread.State.WAITING,thread.getState());
    }
    private static void reject(Environment env)throws Throwable {
        try{env.doFinal();fail("Foreign target/unknown reservation must reject before effects");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("Foreign-family"));}
        assertFalse(flag(env,"finalizationStarted"));assertFalse(flag(env,"finalizationComplete"));
    }
    @Test(timeout=15000) public void knownOtherEnvironmentWaitsWithoutFinalizationEffects()throws Throwable {
        var scope=new CompileScope();var a=env(scope);var b=env(scope);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread owner=start("known-a",()->{try(var lease=a.enterCallbackDomain()){entered.countDown();await(release);}},failure);await(entered);
        Thread retiring=start("retire-b",b::doFinal,failure);
        try{waiting(retiring);assertFalse(flag(b,"finalizationStarted"));}finally{release.countDown();join(owner);join(retiring);a.doFinal();}
        assertNull(failure.get());assertTrue(flag(b,"finalizationComplete"));
    }
    @Test(timeout=15000) public void ownActiveEnvironmentRejectsThenRetrySucceeds()throws Throwable {
        var a=env(new CompileScope());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread owner=start("active-a",()->{try(var lease=a.enterCallbackDomain()){entered.countDown();await(release);}},failure);await(entered);
        try{reject(a);}finally{release.countDown();join(owner);a.doFinal();}assertNull(failure.get());assertTrue(flag(a,"finalizationComplete"));
    }
    @Test(timeout=15000) public void parkedOwnEnvironmentRejectsButUnrelatedEnvironmentCanRetire()throws Throwable {
        var scope=new CompileScope();var a=env(scope);var b=env(scope);var parked=new CountDownLatch(1);var restore=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread owner=start("park-a",()->{try(var lease=a.enterCallbackDomain()){lease.includeBody();try(var park=scope.getClassLoader().getCallbackDomain().parkForHostLifecycle()){parked.countDown();await(restore);}}},failure);await(parked);
        try{reject(a);b.doFinal();assertTrue(flag(b,"finalizationComplete"));}finally{restore.countDown();join(owner);a.doFinal();}assertNull(failure.get());
    }
    @Test(timeout=15000) public void targetParticipantArrivingDuringWaitWakesAndRejects()throws Throwable {
        var scope=new CompileScope();var a=env(scope);var b=env(scope);var failure=new AtomicReference<Throwable>();var result=new AtomicReference<Throwable>();var finished=new CountDownLatch(1);
        Thread retiring;
        try(var outer=a.enterCallbackDomain()) {
            retiring=start("retire-b-target-arrives",()->{try{b.doFinal();result.set(new AssertionError("Target entered but retirement completed"));}catch(IllegalStateException expected){result.set(expected);}finally{finished.countDown();}},failure);
            waiting(retiring);
            try(var target=b.enterCallbackDomain()){await(finished);assertTrue(result.get()instanceof IllegalStateException);assertTrue(result.get().getMessage().contains("Foreign-family"));assertFalse(flag(b,"finalizationStarted"));}
        }
        join(retiring);a.doFinal();b.doFinal();assertNull(failure.get());
    }
    @Test(timeout=15000) public void callbackCanFinalizeWaitingOtherEnvironmentWithoutLockInversion()throws Throwable {
        var scope=new CompileScope();var a=env(scope);var b=env(scope);var failure=new AtomicReference<Throwable>();Thread retiring;
        try(var outer=a.enterCallbackDomain()) {
            retiring=start("retire-b-callback",b::doFinal,failure);waiting(retiring);
            b.doFinal();assertTrue(flag(b,"finalizationComplete"));
        }
        join(retiring);a.doFinal();assertNull(failure.get());
    }
    @Test(timeout=15000) public void concurrentFinalizersWaitForOneActualOwner()throws Throwable {
        var a=env(new CompileScope());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var effects=new AtomicInteger();var failure=new AtomicReference<Throwable>();
        a.registerShutdownFunction(new php.runtime.env.handler.ShutdownHandler(null,new php.runtime.Memory[0]){@Override public void call()throws Throwable{effects.incrementAndGet();entered.countDown();await(release);}});
        Thread first=start("finalizer-first",a::doFinal,failure);await(entered);Thread second=start("finalizer-second",a::doFinal,failure);
        try{waiting(second);assertEquals(1,effects.get());}finally{release.countDown();join(first);join(second);}
        assertNull(failure.get());assertEquals(1,effects.get());assertTrue(flag(a,"finalizationComplete"));
    }
    @Test(timeout=15000) public void untaggedForeignReservationRemainsStrict()throws Throwable {
        var scope=new CompileScope();var a=env(scope);var domain=scope.getClassLoader().getCallbackDomain();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread owner=start("untagged-owner",()->{try(var lease=domain.enter()){entered.countDown();await(release);}},failure);await(entered);
        try{reject(a);}finally{release.countDown();join(owner);a.doFinal();}assertNull(failure.get());
    }
    @Test(timeout=15000) public void clearedActiveParticipantRemainsStrictWhilePhysicalGateIsFree()throws Throwable {
        var scope=new CompileScope();var a=env(scope);var b=env(scope);var domain=scope.getClassLoader().getCallbackDomain();var parked=new CountDownLatch(1);var restore=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread owner=start("cleared-park",()->{try(var lease=a.enterCallbackDomain()){lease.includeBody();try(var park=domain.parkForHostLifecycle()){parked.countDown();await(restore);}}},failure);await(parked);
        try {
            Field gate=PhpCallbackDomain.class.getDeclaredField("admission");gate.setAccessible(true);Field records=PhpCallbackDomain.class.getDeclaredField("participants");records.setAccessible(true);
            synchronized(gate.get(domain)) {
                boolean cleared=false;
                for(Object record:(java.util.List<?>)records.get(domain)){
                    Field weak=record.getClass().getDeclaredField("environment");weak.setAccessible(true);var reference=(WeakReference<?>)weak.get(record);
                    if(reference.get()==a){reference.clear();cleared=true;}
                }
                assertTrue("Deterministic cleared-reference state on a real active parked admission",cleared);
            }
            reject(b);
        } finally {restore.countDown();join(owner);a.doFinal();b.doFinal();}assertNull(failure.get());
    }
    private static WeakReference<Environment> retireAndDrop(CompileScope scope)throws Throwable {var value=env(scope);try(var lease=value.enterCallbackDomain()){}value.doFinal();return new WeakReference<>(value);}
    @Test(timeout=15000) public void idleWeakParticipantCacheDoesNotRootEnvironment()throws Throwable {
        var scope=new CompileScope();WeakReference<Environment> weak=retireAndDrop(scope);
        for(int i=0;i<100&&weak.get()!=null;i++){System.gc();Thread.sleep(20);}
        assertNull("Loader-owned weak idle metadata must not retain Environment",weak.get());
        var next=env(scope);next.doFinal();java.lang.ref.Reference.reachabilityFence(scope);
    }
    @Test(timeout=15000) public void rawDomainRetirementContractIsStillFailFast()throws Throwable {
        var domain=new PhpCallbackDomain();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread owner=start("raw-domain-owner",()->{try(var lease=domain.enter()){entered.countDown();await(release);}},failure);await(entered);
        try{try(var lease=domain.enterForRetirement()){fail("Raw retirement cannot wait for foreign admission");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Foreign-family"));}}
        finally{release.countDown();join(owner);}try(var lease=domain.enterForRetirement()){}assertNull(failure.get());
    }
    private static Environment lookupContext(CompileScope scope)throws Exception {
        Field field=CompileScope.class.getDeclaredField("classLookupEnvironment");field.setAccessible(true);return ((ThreadLocal<Environment>)field.get(scope)).get();
    }
    @Test(timeout=15000) public void explicitScopeContextPreservesVirtualDispatchAndNestedFailureRestoration()throws Throwable {
        Environment[] values=new Environment[2];var calls=new AtomicInteger();RuntimeException sentinel=new RuntimeException("lookup-sentinel");
        var scope=new CompileScope(){@Override public php.runtime.reflection.ClassEntity fetchUserClass(String name,String lower,boolean modern){
            try {
                if(name.equals("__outer_context")) {
                    calls.incrementAndGet();assertSame(values[0],lookupContext(this));
                    fetchUserClass(values[1],"__inner_context","__inner_context",modern);assertSame(values[0],lookupContext(this));
                    try{fetchUserClass(values[1],"__throw_context","__throw_context",modern);fail();}catch(RuntimeException failure){assertSame(sentinel,failure);}
                    assertSame(values[0],lookupContext(this));return null;
                }
                if(name.equals("__inner_context")){calls.incrementAndGet();assertSame(values[1],lookupContext(this));return null;}
                if(name.equals("__throw_context")){calls.incrementAndGet();assertSame(values[1],lookupContext(this));throw sentinel;}
            }catch(RuntimeException|Error failure){throw failure;}catch(Exception failure){throw new AssertionError(failure);}
            return super.fetchUserClass(name,lower,modern);
        }};
        values[0]=env(scope);values[1]=env(scope);
        try{scope.fetchUserClass(values[0],"__outer_context","__outer_context",false);assertEquals(3,calls.get());assertNull(lookupContext(scope));}
        finally{values[0].doFinal();values[1].doFinal();}
    }
    @Test(timeout=15000) public void foreignScopeEnvironmentRejectsBeforeVirtualDispatch()throws Throwable {
        var calls=new AtomicInteger();var scope=new CompileScope(){@Override public php.runtime.reflection.ClassEntity fetchUserClass(String n,String l,boolean m){calls.incrementAndGet();return super.fetchUserClass(n,l,m);}};
        var foreign=env(new CompileScope());int before=calls.get();
        try{try{scope.fetchUserClass(foreign,"__foreign","__foreign",false);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("another CompileScope"));}assertEquals(before,calls.get());assertNull(lookupContext(scope));}
        finally{foreign.doFinal();}
    }
    @Test(timeout=15000) public void directScopeCallbackRemainsUntaggedAndStrict()throws Throwable {
        var scope=new CompileScope();var b=env(scope);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        scope.addClassEntityFetchHandler(new php.runtime.env.handler.EntityFetchHandler(){@Override public void fetch(CompileScope actual,String name,String lower){if(name.equals("__raw_scope_probe"))try{assertNull(lookupContext(actual));entered.countDown();await(release);}catch(Exception problem){throw new AssertionError(problem);}}});
        Thread owner=start("raw-scope-callback",()->scope.fetchUserClass("__raw_scope_probe","__raw_scope_probe",false),failure);await(entered);
        try{reject(b);}finally{release.countDown();join(owner);b.doFinal();}assertNull(failure.get());assertNull(lookupContext(scope));
    }
    @Test(timeout=15000) public void actualEnvironmentScopeCallbackKeepsItsExactProducerAttribution()throws Throwable {
        var scope=new CompileScope();var a=env(scope);var b=env(scope);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        scope.addClassEntityFetchHandler(new php.runtime.env.handler.EntityFetchHandler(){@Override public void fetch(CompileScope actual,String name,String lower){if(name.equals("__typed_scope_probe"))try{assertSame(a,lookupContext(actual));entered.countDown();await(release);}catch(Exception problem){throw new AssertionError(problem);}}});
        Thread owner=start("typed-scope-callback",()->{a.fetchClass("__typed_scope_probe",false);assertNull(lookupContext(scope));},failure);await(entered);Thread retiring=start("retire-during-typed-scope",b::doFinal,failure);
        try{waiting(retiring);assertFalse(flag(b,"finalizationStarted"));}finally{release.countDown();join(owner);join(retiring);a.doFinal();}
        assertNull(failure.get());assertTrue(flag(b,"finalizationComplete"));
    }
}
