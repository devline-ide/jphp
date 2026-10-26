package php.runtime.env;

import org.junit.Test;
import php.runtime.ext.core.classes.time.WrapTimer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class TimerLifecycleTest {
    private Timer timer() throws Exception {
        var field = WrapTimer.class.getDeclaredField("timer");
        field.setAccessible(true);
        return (Timer) field.get(null);
    }

    @Test public void scheduledCallbacksRunOnDaemonThreadsAfterEveryReset() throws Exception {
        try {
            for (int i = 0; i < 2; i++) {
                WrapTimer.cancelAll();
                var called = new CountDownLatch(1);
                var daemon = new AtomicBoolean();
                timer().schedule(new TimerTask() {
                    @Override public void run() {
                        daemon.set(Thread.currentThread().isDaemon());
                        called.countDown();
                    }
                }, 1);
                assertTrue("scheduled callback must execute", called.await(3, TimeUnit.SECONDS));
                assertTrue("timer callback must not keep the JVM alive", daemon.get());
            }
        } finally { WrapTimer.shutdownAll(); }
    }

    @Test public void idleTimerDoesNotPreventNaturalProcessExit() throws Exception {
        String classpath = Path.of(TimerLifecycleTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(WrapTimer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        for (String mode : new String[]{"initial", "reset"}) {
            Process child = new ProcessBuilder(java, "-cp", classpath, Probe.class.getName(), mode)
                    .redirectErrorStream(true).start();
            try {
                assertTrue("timer-only JVM did not exit naturally: " + mode, child.waitFor(5, TimeUnit.SECONDS));
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(output, 0, child.exitValue());
                assertTrue(output, output.contains("TIMER_READY"));
            } finally {
                if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); }
            }
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Class.forName(WrapTimer.class.getName());
            if (args[0].equals("reset")) WrapTimer.cancelAll();
            System.out.println("TIMER_READY");
        }
    }
}
