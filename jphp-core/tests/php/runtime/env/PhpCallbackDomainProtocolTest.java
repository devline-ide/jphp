package php.runtime.env;

import org.junit.Test;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Portable protocol prototype tests only; these do not attest production Fiber integration. */
public class PhpCallbackDomainProtocolTest {
    private static Thread start(String name,Runnable action,AtomicReference<Throwable> failure) {
        Thread thread=new Thread(()->{try{action.run();}catch(Throwable caught){failure.compareAndSet(null,caught);}},"jphp-protocol-"+name);
        thread.setDaemon(true);thread.start();return thread;
    }
    private static void await(CountDownLatch latch) {
        try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static void join(Thread thread)throws InterruptedException {thread.join(5000);assertFalse(thread.isAlive());}

    @Test(timeout=15000) public void sameFamilyReentersOnAnotherCarrierWithoutKeepingJavaMonitor()throws Exception {
        var gate=new PhpOperationGate();var token=PhpExecutionLineage.current();var failure=new AtomicReference<Throwable>();
        try(var outer=gate.enter(token,PhpOperationGate.Reentry.SAME_LINEAGE)) {
            assertFalse(Thread.holdsLock(gate));
            Thread child=start("family",()->{try(var binding=PhpExecutionLineage.bind(token);var inner=gate.enter(PhpExecutionLineage.current(),PhpOperationGate.Reentry.SAME_LINEAGE)){assertFalse(Thread.holdsLock(gate));}},failure);
            join(child);assertNull(failure.get());
        }
        try(var next=gate.enter(new Object(),PhpOperationGate.Reentry.SAME_LINEAGE)){assertFalse(Thread.holdsLock(gate));}
    }
    @Test(timeout=15000) public void unrelatedHostWaitsUntilExactOwnerReleases()throws Exception {
        var domain=new PhpCallbackDomain();var entered=new CountDownLatch(1);var attempted=new CountDownLatch(1);var release=new CountDownLatch(1);var second=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread first=start("first",()->{try(var lease=domain.enter()){entered.countDown();await(release);}},failure);await(entered);
        Thread other=start("other",()->{attempted.countDown();try(var lease=domain.enter()){second.countDown();}},failure);
        try{await(attempted);assertFalse(second.await(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}
        join(first);join(other);assertNull(failure.get());assertEquals(0,second.getCount());
    }
    @Test(timeout=15000) public void crossDomainEntryRejectsBeforeBodyAndReleasesClaim() {
        var first=new PhpCallbackDomain();var other=new PhpCallbackDomain();var effects=new AtomicInteger();
        try(var lease=first.enter()) {
            try(var forbidden=other.enter()){effects.incrementAndGet();fail("Cross-domain callback was admitted");}
            catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("cross runtime domains"));}
            assertEquals(0,effects.get());
        }
        try(var lease=other.enter()){effects.incrementAndGet();}assertEquals(1,effects.get());
    }
    @Test(timeout=15000) public void foreignRetirementRejectsBeforeMutationThenRetryWorks()throws Exception {
        var domain=new PhpCallbackDomain();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();var effects=new AtomicInteger();
        Thread first=start("parked-owner",()->{try(var lease=domain.enter()){entered.countDown();await(release);}},failure);await(entered);
        try {
            try(var forbidden=domain.enterForRetirement()){effects.incrementAndGet();fail("Foreign retirement waited/admitted");}
            catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Foreign-family"));}
            assertEquals(0,effects.get());
        }finally{release.countDown();join(first);}
        assertNull(failure.get());try(var allowed=domain.enterForRetirement()){effects.incrementAndGet();}assertEquals(1,effects.get());
    }
    @Test(timeout=15000) public void strictHostSinkLeaseRejectsSameFamilyBeforeSecondMutation()throws Exception {
        var gate=new PhpOperationGate();var token=PhpExecutionLineage.current();var effects=new AtomicInteger();var failure=new AtomicReference<Throwable>();
        try(var outer=gate.enter(token,PhpOperationGate.Reentry.REJECT)) {
            effects.incrementAndGet();
            try(var forbidden=gate.enter(token,PhpOperationGate.Reentry.REJECT)){effects.incrementAndGet();fail();}catch(PhpOperationGate.OverlapException expected){}
            Thread child=start("strict-child",()->{try(var binding=PhpExecutionLineage.bind(token)){
                try(var forbidden=gate.enter(PhpExecutionLineage.current(),PhpOperationGate.Reentry.REJECT)){effects.incrementAndGet();fail();}catch(PhpOperationGate.OverlapException expected){}
            }},failure);
            join(child);assertNull(failure.get());assertEquals(1,effects.get());
        }
    }
    private static WeakReference<PhpCallbackDomain> abandonDomain() {
        var domain=new PhpCallbackDomain();domain.enter(); // Intentionally unreachable reservation; no PHP execution in this raw prototype.
        return new WeakReference<>(domain);
    }
    private static void collect(WeakReference<?> reference)throws InterruptedException {
        for(int i=0;i<120&&reference.get()!=null;i++){System.gc();System.runFinalization();Thread.sleep(25);}
        assertNull("Prototype metadata strongly rooted unreachable state",reference.get());
    }
    @Test(timeout=15000) public void reachableRootTokenDoesNotRootAbandonedDomain()throws Exception {
        var token=PhpExecutionLineage.current();WeakReference<PhpCallbackDomain> abandoned=abandonDomain();collect(abandoned);
        // Cleared weak domain/depth must not prevent a later independent domain claim.
        try(var next=new PhpCallbackDomain().enter()){}Reference.reachabilityFence(token);
    }
    private static WeakReference<Thread> parkOrphanCarrier(PhpOperationGate gate,Object token)throws InterruptedException {
        var entered=new CountDownLatch(1);
        Thread thread=Thread.ofVirtual().start(()->{
            try(var lease=gate.enter(token,PhpOperationGate.Reentry.SAME_LINEAGE)) {
                var queue=new LinkedBlockingQueue<Object>();entered.countDown();queue.take();
            }catch(InterruptedException expected){Thread.currentThread().interrupt();}
        });
        await(entered);return new WeakReference<>(thread);
    }
    private static void orphanWeakMetadataBody()throws Exception {
        var gate=new PhpOperationGate();var token=new Object();WeakReference<Thread> carrier=parkOrphanCarrier(gate,token);collect(carrier);
        // Collection is not permission to erase a reserved callback operation.
        assertNull(gate.tryEnter(new Object(),PhpOperationGate.Reentry.SAME_LINEAGE));
        Reference.reachabilityFence(gate);Reference.reachabilityFence(token);
    }
    private static String orphanCodeSource(Class<?> type)throws Exception {
        return java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().toString();
    }
    /** Child-only entry: JDK default virtual-thread tracking itself is a strong root. */
    public static void main(String[] args)throws Exception {
        assertEquals(2,args.length);assertEquals("orphan-weak-metadata",args[0]);
        assertEquals("false",System.getProperty("jdk.trackAllThreads"));
        assertEquals(args[1],orphanCodeSource(PhpOperationGate.class));
        orphanWeakMetadataBody();
        System.out.println("ORPHAN_WEAK_METADATA_AND_RESERVED_OPERATION_PASS");
    }
    @Test(timeout=30000) public void liveGateDoesNotStronglyRootOrphanVirtualCarrier()throws Exception {
        var entries=new java.util.LinkedHashSet<String>();
        for(Class<?> type:new Class<?>[]{PhpCallbackDomainProtocolTest.class,PhpOperationGate.class,org.junit.Test.class,org.hamcrest.Matcher.class})entries.add(orphanCodeSource(type));
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var command=new java.util.ArrayList<String>();
        command.add(java.nio.file.Path.of(System.getProperty("java.home"),"bin",executable).toString());
        command.add("-Xverify:all");command.add("-Xmx64m");command.add("-Djava.awt.headless=true");
        command.add("-Djdk.trackAllThreads=false");command.add("-cp");command.add(String.join(java.io.File.pathSeparator,entries));
        command.add(PhpCallbackDomainProtocolTest.class.getName());command.add("orphan-weak-metadata");command.add(orphanCodeSource(PhpOperationGate.class));
        var log=java.nio.file.Files.createTempFile("jphp-orphan-virtual-carrier-",".log");
        Process child=null;boolean passed=false;
        try {
            var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            for(String key:new String[]{"JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"})builder.environment().remove(key);
            child=builder.start();
            assertTrue("Owned orphan metadata child timed out; log: "+log,child.waitFor(15,TimeUnit.SECONDS));
            String output=java.nio.file.Files.readString(log,java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("Owned orphan metadata child failed; log: "+log+"\n"+output,0,child.exitValue());
            assertEquals("Missing child proof; log: "+log+"\n"+output,1,output.lines().filter("ORPHAN_WEAK_METADATA_AND_RESERVED_OPERATION_PASS"::equals).count());
            passed=true;
        } finally {
            if(child!=null&&child.isAlive()) {child.destroyForcibly();assertTrue("Owned orphan metadata child did not terminate",child.waitFor(5,TimeUnit.SECONDS));}
            if(passed)java.nio.file.Files.delete(log);
        }
    }
}
