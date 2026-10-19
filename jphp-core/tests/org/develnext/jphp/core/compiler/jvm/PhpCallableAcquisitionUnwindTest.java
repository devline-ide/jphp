package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import static org.junit.Assert.*;

/** Negative native-boundary controls: engine transport is never a PHP validation failure. */
public class PhpCallableAcquisitionUnwindTest {
    private static final class LookupEnvironment extends Environment {
        private final RuntimeException signal;
        LookupEnvironment(CompileScope scope,RuntimeException signal) {super(scope);this.signal=signal;}
        @Override public ClassEntity fetchClass(String name,String lowerName,boolean autoload) {
            if(autoload && "SignalTarget".equals(name))throw signal;
            return super.fetchClass(name,lowerName,autoload);
        }
    }
    private static void check(int api)throws Throwable {
        RuntimeException[] signals={new php.runtime.exceptions.PhpUnwindSignal(){},new php.runtime.exceptions.FinallyException()};
        for(var signal:signals) {
            var scope=new CompileScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
            var env=new LookupEnvironment(scope,signal);int before=env.getCallStackTop();
            try {
                Memory callback=new StringMemory("SignalTarget::run");
                try {
                    if(api==0)php.runtime.invoke.PhpCallableFactory.fromCallable(callback,env,TraceInfo.UNKNOWN);
                    else if(api==1)php.runtime.ext.core.LangFunctions.call_user_func(env,TraceInfo.UNKNOWN,callback);
                    else php.runtime.ext.core.LangFunctions.call_user_func_array(env,TraceInfo.UNKNOWN,callback,new ArrayMemory());
                    fail("Engine signal was swallowed");
                }catch(Throwable actual){assertSame(signal,actual);}
                assertEquals("Native acquisition left a synthetic call frame",before,env.getCallStackTop());
            }finally{env.doFinal();}
        }
    }
    @Test public void closure_preserves_engine_transport()throws Throwable{check(0);}
    @Test public void cuf_preserves_engine_transport()throws Throwable{check(1);}
    @Test public void cufa_preserves_engine_transport()throws Throwable{check(2);}
}
