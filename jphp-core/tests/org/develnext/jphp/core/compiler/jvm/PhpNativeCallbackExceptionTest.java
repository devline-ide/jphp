package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.java.JavaException;
import php.runtime.invoke.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class PhpNativeCallbackExceptionTest {
    private static final class Signal extends php.runtime.exceptions.PhpUnwindSignal {}
    private static Environment environment(PhpLanguageProfile profile) {
        var scope=new CompileScope();scope.setPhpLanguageProfile(profile);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope,new ByteArrayOutputStream());
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> Memory raise(Throwable failure)throws T {throw(T)failure;}
    private static Memory callback(Environment env,Throwable failure) {
        return RunnableInvoker.make(env,new php.runtime.common.Callback<Memory,Memory[]>() {
            @Override public Memory call(Memory[] arguments){return raise(failure);}
        });
    }
    private static Throwable foreignFailure(int kind) {
        return switch(kind){case 0->new IllegalStateException("native-runtime");case 1->new InterruptedException("native-checked");default->new AssertionError("native-error");};
    }
    private static Memory execute(Environment env,PhpLanguageProfile profile,String source,boolean debug,boolean phb)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(profile);
        var module=new JvmCompiler(env,context).compile(debug);
        if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module.include(env);
    }
    @Test public void foreignCallbackFailuresEnterPhpCatchAndNestedFinallySourcePhb()throws Throwable {
        var failures=new ArrayList<String>();int variants=0;
        for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})
            for(boolean internal:new boolean[]{false,true})for(int kind=0;kind<3;kind++) {
                variants++;Environment env=environment(profile);Throwable original=foreignFailure(kind);
                try {
                    env.getGlobals().refOfIndex("probe").assign(callback(env,original));
                    String call=internal?"call_user_func($probe)":"$probe()";
                    String source="$probe=$GLOBALS['probe'];try{try{"+call+";}finally{$GLOBALS['inner_finally']=1;}}catch(php\\lang\\JavaException $e){$GLOBALS['caught']=$e;}finally{$GLOBALS['outer_finally']=1;}return $GLOBALS['caught'];";
                    var caught=execute(env,profile,source,debug,phb).toValue(ObjectMemory.class).value;
                    assertTrue(caught instanceof JavaException);
                    Throwable actual=((JavaException)caught).getThrowable();
                    // Legacy direct __invoke uses callNoThrow's existing RuntimeException wrapper.
                    if(profile==PhpLanguageProfile.JPHP_LEGACY)while(actual.getCause()!=null&&actual.getCause()!=actual)actual=actual.getCause();
                    assertSame(original,actual);
                    assertEquals(1,env.getGlobals().valueOfIndex("inner_finally").toInteger());
                    assertEquals(1,env.getGlobals().valueOfIndex("outer_finally").toInteger());
                }catch(Throwable failure){failures.add(profile+"/"+debug+"/"+phb+"/"+internal+"/"+kind+": "+failure);}
                finally{env.doFinal();}
            }
        assertEquals(48,variants);assertTrue(String.join("\n",failures),failures.isEmpty());
    }
    @Test public void allFrameFallbackRoutesPreserveForeignThrowableInsidePhpWrapper()throws Throwable {
        int variants=0;
        for(var profile:PhpLanguageProfile.values())for(int route=0;route<6;route++)for(int kind=0;kind<3;kind++) {
            Environment env=environment(profile);Throwable original=foreignFailure(kind);variants++;
            try {
                Invoker invoker=Invoker.create(env,callback(env,original));
                try(var frame=PhpCallFrame.resolvedInvoker(env,TraceInfo.UNKNOWN,invoker,0)) {
                    try {
                        switch(route){case 0->frame.invoke();case 1->frame.invokeDiscarded();case 2->frame.invokePrepared(new Memory[0]);case 3->frame.invokePreparedDiscarded(new Memory[0]);case 4->frame.invokeInternal(new Memory[0]);default->frame.invokeInternalDiscarded(new Memory[0]);}
                        fail("Native failure swallowed");
                    }catch(JavaException normalized){assertSame(original,normalized.getThrowable());}
                }
            }finally{env.doFinal();}
        }
        assertEquals(36,variants);
    }
    @Test public void nativeUnwindAndPhpThrowableKeepTheirOriginalIdentity()throws Throwable {
        for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            Environment env=environment(profile);
            try {
                var signal=new Signal();env.getGlobals().refOfIndex("probe").assign(callback(env,signal));
                String source="$probe=$GLOBALS['probe'];try{$probe();}catch(Throwable $e){$GLOBALS['caught']=$e;}finally{$GLOBALS['finally']=1;}return $GLOBALS['caught']??null;";
                try{execute(env,profile,source,debug,phb);fail("Internal unwind entered PHP catch");}
                catch(Signal actual){assertSame(signal,actual);}
                assertEquals(1,env.getGlobals().valueOfIndex("finally").toInteger());
                assertTrue(env.getGlobals().valueOfIndex("caught").isNull());
            }finally{env.doFinal();}
            env=environment(profile);
            try {
                php.runtime.lang.exception.BaseError original;
                try{env.exception(TraceInfo.UNKNOWN,php.runtime.lang.exception.BaseError.class,"native-php-error");throw new AssertionError();}
                catch(php.runtime.lang.exception.BaseError error){original=error;}
                env.getGlobals().refOfIndex("probe").assign(callback(env,original));
                var caught=execute(env,profile,"$probe=$GLOBALS['probe'];try{$probe();}catch(Error $e){$GLOBALS['caught']=$e;}finally{$GLOBALS['finally']=1;}return $GLOBALS['caught'];",debug,phb).toValue(ObjectMemory.class).value;
                assertSame(original,caught);assertEquals(1,env.getGlobals().valueOfIndex("finally").toInteger());
            }finally{env.doFinal();}
        }
    }
    @Test public void directJavaInvokerCallsKeepRawForeignFailures()throws Throwable {
        Environment env=environment(PhpLanguageProfile.PHP_8_5);
        try {
            for(int kind=0;kind<3;kind++) {
                Throwable original=foreignFailure(kind);
                try{Invoker.create(env,callback(env,original)).call();fail("Native failure swallowed");}
                catch(Throwable actual){assertSame(original,actual);}
            }
        }finally{env.doFinal();}
    }
}
