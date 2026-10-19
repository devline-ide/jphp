package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.memory.ObjectMemory;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;

/** Deterministic generated JVM-entry checks, without requesting physical collection. */
public class PhpJvmFinalizerTest extends JvmCompilerCase {
    private void check(boolean shutdownFirst, boolean failing) throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                String body="$GLOBALS['calls']++;"+(failing?"throw new \\Exception('finalizer-failure');":"");
                var context=new Context("class FinalizerParent{function __destruct(){"+body+"}}class FinalizerChild extends FinalizerParent{}$GLOBALS['calls']=0;return new FinalizerChild();");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var object=((ObjectMemory)module.include(env)).value;
                var entry=object.getClass().getDeclaredMethod("finalize");
                if(shutdownFirst)env.finalizeObjects();
                entry.invoke(object);
                if(!shutdownFirst) {
                    assertFalse("JVM entry must only publish its receiver",object.isFinalized());
                    assertEquals(0,env.getRuntimeGlobalsTable().valueOfIndex("calls").toLong());
                    if(failing) {
                        try{env.collectGarbage();fail("Safe point must execute the failing destructor");}
                        catch(Throwable failure){if(failure instanceof AssertionError)throw failure;assertTrue(String.valueOf(failure).contains("finalizer-failure"));}
                    }else env.collectGarbage();
                }
                assertTrue("Finalized bit must be published before the callback",object.isFinalized());
                assertEquals("debug="+debug+" dump="+dump,1,env.getRuntimeGlobalsTable().valueOfIndex("calls").toLong());
                entry.invoke(object);entry.invoke(object);env.finalizeObjects();
                assertEquals(1,env.getRuntimeGlobalsTable().valueOfIndex("calls").toLong());
            }finally{env.doFinal();}
        }
    }
    @Test public void generatedEntrySkipsAnAlreadyFinalizedObject() throws Throwable {check(true,false);}
    @Test public void generatedEntryRunsBeforeShutdownOnlyOnce() throws Throwable {check(false,false);}
    @Test public void generatedEntryDoesNotRetryAFailedDestructor() throws Throwable {check(false,true);}
}
