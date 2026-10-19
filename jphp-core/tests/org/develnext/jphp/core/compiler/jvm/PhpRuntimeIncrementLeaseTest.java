package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.LangFunctions;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Actual captured bucket mutation through an explicitly registered native callback boundary. */
public class PhpRuntimeIncrementLeaseTest extends JvmCompilerCase {
    private static final ThreadLocal<Probe> PROBE=new ThreadLocal<>();
    private static final class Probe {
        final int operation;
        final php.runtime.exceptions.CriticalException primary=new php.runtime.exceptions.CriticalException("captured increment callback failure");
        Probe(int operation){this.operation=operation;}
    }
    public static final class LeaseExtension extends php.runtime.ext.support.Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new LeaseFunctions());}
    }
    public static final class LeaseFunctions extends php.runtime.ext.support.compile.FunctionsContainer {
        public static Memory runtime_increment_reentry(Environment env)throws Throwable {
            assertNotNull(PhpRuntimeOwnership.currentNativeBody(env));
            Probe probe=PROBE.get();var authority=env.getRuntimeOwnership();
            var root=authority.globalAddress("a");root.incrementArrayCarrier(env,TraceInfo.UNKNOWN);
            var array=root.toValue(ArrayMemory.class);var key=LongMemory.valueOf(probe.operation>=4?0:2);
            var carrier=array.getByScalar(key);
            switch(probe.operation) {
                case 0: case 3: carrier.assign(LongMemory.valueOf(9));break;
                case 1: case 4: array.remove(key);break;
                case 2: carrier.getRuntimeHolder().reference();
                    carrier.getRuntimeHolder().rebind(authority.globalAddress("new").getRuntimeHolder().reference());break;
                case 5: carrier.getRuntimeHolder().rebind(authority.globalAddress("new").getRuntimeHolder().reference());break;
                default:throw new AssertionError(probe.operation);
            }
            if(probe.operation==3)throw probe.primary;
            return Memory.NULL;
        }
    }
    @Test public void actualCallbackUnlinkRebindSharedTargetAndFailureKeepTheirCapturedAddress()throws Throwable {
        for(int operation=0;operation<6;operation++)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);scope.registerExtension(new LeaseExtension());
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            String source="$x=false;$a="+(operation>=4?"[&$x]":"[2=>false]")+";$new=50;$ev=[];set_error_handler(function($n,$s)use(&$ev){$ev[]=[$n,$s];runtime_increment_reentry();return true;});$r=$a["+(operation>=4?0:2)+"]++;restore_error_handler();return 7;";
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
            if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            var authority=new PhpRuntimeOwnership.Authority(env,domain);var probe=new Probe(operation);PROBE.set(probe);
            try(var execution=authority.enter()) {
                try {
                    var nativeMember=LeaseFunctions.class.getMethod("runtime_increment_reentry",Environment.class);
                    authority.registerNative(nativeMember,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(nativeMember,actual);}
                        public void validateArguments(List<PhpRuntimeOwnership.Value> args){assertTrue(args.isEmpty());}
                    });
                    for(var member:LangFunctions.class.getDeclaredMethods())if(Set.of("set_error_handler","restore_error_handler").contains(member.getName()))
                        authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                            public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                            public void validateArguments(List<PhpRuntimeOwnership.Value> args){}
                        });
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,b)->{});
                    authority.registerModule(module,module.getData(),(p,m,b)->{});
                    if(operation==3){try(var ignored=authority.include(module)){fail("Callback failure disappeared");}catch(php.runtime.ext.java.JavaException failure){
                        Throwable primary=failure.getThrowable();while(primary.getCause()!=null)primary=primary.getCause();assertSame(probe.primary,primary);
                    }}
                    else try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}
                    var a=authority.globalAddress("a").toValue(ArrayMemory.class);
                    if(operation==1||operation==4)assertEquals(0,a.size());
                    else if(operation==5){assertEquals(50,a.getByScalar(LongMemory.valueOf(0)).toLong());assertSame(Memory.FALSE,authority.globalAddress("x").toValue());}
                    else assertSame(Memory.FALSE,a.getByScalar(LongMemory.valueOf(2)).toValue());
                    assertEquals(50,authority.globalAddress("new").toLong());
                    assertEquals(1,authority.globalAddress("ev").toValue(ArrayMemory.class).size());
                    assertNull(PhpRuntimeOwnership.currentNativeBody(env));
                }catch(Throwable failure){throw new AssertionError("operation="+operation+" debug="+debug+" dump="+dump,failure);}
                finally{env.doFinal();authority.close();PROBE.remove();}
                assertEquals(0,domain.graph().liveNodes());
            }
        }
    }
    @Test public void passiveCaptureCannotMintRuntimeScalarWriteAuthority()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
        var authority=new PhpRuntimeOwnership.Authority(env,domain);
        try(var execution=authority.enter()) {
            try(var frame=authority.frame()) {
                var array=PhpOwnershipArrays.runtimeFresh(frame,4_000_810,new ArrayMemory());
                var carrier=array.refOfIndex(TraceInfo.UNKNOWN,LongMemory.valueOf(2));carrier.assign(Memory.FALSE);
                var holder=((ReferenceMemory)carrier).getRuntimeHolder();var token=((ReferenceMemory)carrier).captureIncrementCell();
                long incoming=array.getRuntimeOrigin().node().incoming();
                assertThrows(IllegalArgumentException.class,()->holder.captureIncrement(token));
                assertThrows(IllegalArgumentException.class,token::runtimeCarrier);
                assertThrows(IllegalStateException.class,()->token.commit(LongMemory.valueOf(9)));
                assertThrows(IllegalStateException.class,()->token.restore(LongMemory.valueOf(9)));
                assertSame(Memory.FALSE,carrier.toValue());assertEquals(incoming,array.getRuntimeOrigin().node().incoming());assertEquals(1,array.size());
            }finally{env.doFinal();authority.close();}
            assertEquals(0,domain.graph().liveNodes());
        }
    }
}
