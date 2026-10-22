package php.runtime.output;

import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** The idle cache is a speculative route hint, never an admission or native-callback capability. */
public class PhpOutputIdleProbeHintTest {
    private static AutoCloseable open(OutputBuffer root) throws Throwable {
        Method method=OutputBuffer.class.getDeclaredMethod("operation",boolean.class);method.setAccessible(true);
        try{return (AutoCloseable)method.invoke(root,false);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    private static Object cached(OutputBuffer root) throws Exception {
        Field field=OutputBuffer.class.getDeclaredField("idleOperation");field.setAccessible(true);
        return ((AtomicReference<?>)field.get(root)).get();
    }
    @Test(timeout=15000) public void cachedIdleWrapperDuringAnotherActiveWriterStillPreservesNativeCallbackGuards() throws Throwable {
        AtomicReference<OutputBuffer> root=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicInteger rejections=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        OutputStream sink=new OutputStream(){
            @Override public void write(int value){throw new AssertionError("Unexpected byte-at-a-time sink call");}
            @Override public void write(byte[] value,int offset,int length){
                bytes.write(value,offset,length);entered.countDown();
                try{
                    assertTrue(release.await(5,TimeUnit.SECONDS));
                    assertNotNull("Closed A must be cached while writer B is still active",cached(root.get()));
                    try{root.get().write("forbidden");fail("Native sink callback mutation must reject even when idle cache is nonnull");}
                    catch(IllegalStateException expected){rejections.incrementAndGet();}
                    assertTrue("Read-only access remains allowed under the real carrier admission",root.get().getBufferSize()>=0);
                }catch(Throwable problem){failure.compareAndSet(null,problem);}
            }
        };
        Environment env=new Environment(new CompileScope(),sink);root.set(env.getDefaultBuffer());root.get().setImplicitFlush(true);
        root.get().setPhp85Capabilities(112);
        assertEquals(112,root.get().getCapabilities());
        AutoCloseable first=open(root.get());boolean firstClosed=false;
        Thread writer=new Thread(()->{try{root.get().write("ok");}catch(Throwable problem){failure.compareAndSet(null,problem);}},"idle-probe-hint-owned-writer");
        writer.setDaemon(true);
        try{
            writer.start();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(writer.isAlive()&&writer.getState()!=Thread.State.WAITING&&System.nanoTime()<until)Thread.sleep(1);
            assertEquals("Writer B waits on A's actual domain admission",Thread.State.WAITING,writer.getState());
            first.close();firstClosed=true;
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertNotNull(cached(root.get()));
        }finally{
            release.countDown();if(!firstClosed)first.close();writer.join(5000);
            try{assertFalse("Owned writer remains live",writer.isAlive());root.get().cleanPhp85(true);}finally{env.doFinal();}
        }
        if(failure.get()!=null)throw failure.get();
        assertEquals(1,rejections.get());assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8),bytes.toByteArray());
    }
}
