package php.runtime.env;

import org.junit.Test;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.memory.StringMemory;
import php.runtime.output.OutputBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Semantic integration of cached admission and stack-owned lifecycle parking. */
public class PhpHostLifecycleCacheIntegrationTest {
    private static void rejected(PhpCallbackDomain domain) throws Exception {
        boolean rejected=false;
        try(var forbidden=domain.parkForHostLifecycle()){}
        catch(PhpOperationGate.OverlapException expected){rejected=true;}
        assertTrue("A retained non-include guard must never become parkable",rejected);
    }
    @Test(timeout=15000) public void idleCarrierDoesNotBlockQuiescentLifecyclePark() throws Exception {
        var domain=new PhpCallbackDomain();try(var warm=domain.enter()){}
        var failed=new AtomicReference<Throwable>();
        try(var parked=domain.parkForHostLifecycle()) {
            Thread callback=Thread.ofPlatform().name("owned-independent-lifecycle-callback").unstarted(()->{
                try(var admitted=domain.enter()){}catch(Throwable failure){failed.set(failure);}
            });
            callback.start();callback.join(5000);assertFalse(callback.isAlive());assertNull(failed.get());
        }
        try(var after=domain.enter()){}
    }
    @Test(timeout=15000) public void includeRoleDoesNotLeakThroughCachedCarrier() throws Exception {
        var domain=new PhpCallbackDomain();
        for(int i=0;i<3;i++) {
            try(var include=domain.enter()){include.includeBody();try(var parked=domain.parkForHostLifecycle()){}}
            try(var guard=domain.enter()){rejected(domain);}
        }
    }
    @Test(timeout=15000) public void nestedIncludeKeepsOuterGuardNonParkable() throws Exception {
        var domain=new PhpCallbackDomain();
        try(var outer=domain.enter()) {
            try(var include=domain.enter()){include.includeBody();rejected(domain);}
            rejected(domain);
        }
        try(var after=domain.parkForHostLifecycle()){}
    }
    @Test(timeout=15000) public void rootOutputNestedReuseKeepsNonParkableDomainGuard() throws Throwable {
        var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
        var env=new Environment(scope,new ByteArrayOutputStream());var domain=scope.getClassLoader().getCallbackDomain();
        try {
            class Probe extends OutputBuffer {
                int calls;
                Probe(){super(env,null,null,0,true);}
                @Override protected void _write(byte[] bytes,int length)throws Throwable {
                    calls++;rejected(domain);super._write(bytes,length);
                }
            }
            var output=new Probe();
            try(var include=domain.enter()) {
                include.includeBody();output.write(StringMemory.valueOf("a"));output.write("b");output.write(new byte[]{'c'});
            }
            assertEquals(3,output.calls);assertEquals("abc",output.getContents().toString());
        } finally {env.doFinal();}
    }
}
