package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.invoke.PhpObjectLifecycle;
import php.runtime.lang.IObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import static org.junit.Assert.*;

/** Callback failure/suppression rules from pinned PHP 8.5.11; general last-owner timing is tested separately. */
public class PhpSerializationRestorationTest {
    private interface Check {void verify(Environment env, Memory result) throws Throwable;}
    private void modes(String source,Check check) throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);check.verify(env,module.include(env));
            } finally {env.doFinal();}
        }
    }
    private IObject object(Memory value) {return value.toValue(ObjectMemory.class).value;}
    @Test public void throwingWakeupAndModernHooksSuppressEscapedObjectDestructor() throws Throwable {
        for (String hook:new String[]{"__wakeup()","__unserialize(array $data):void"})
            modes("$escaped=null;$events=[];class RestoreFailure {function "+hook+"{global $escaped;$escaped=$this;throw new Exception('stop');}"
                    +"function __destruct(){global $events;$events[]='destroy';}}try{unserialize('O:14:\"RestoreFailure\":0:{}');}catch(Throwable $e){}return[$escaped,$events];",
                    (env,result)->{
                        var target=object(result.valueOfIndex(0));assertTrue(target.isFinalized());
                        assertFalse(PhpObjectLifecycle.destruct(target,env,PhpObjectLifecycle.Reason.OWNERSHIP_RELEASE));
                        assertEquals(0,result.valueOfIndex(1).toValue(ArrayMemory.class).size());
                    });
    }
    @Test public void malformedCompletedObjectRunsQueuedHookAndPreservesItsException() throws Throwable {
        modes("$events=[];class RestoreParse {function __wakeup(){global $events;$events[]='wake';throw new Exception('wake-stop');}function __destruct(){global $events;$events[]='destroy';}}"
                +"set_error_handler(function($s,$m){global $events;$events[]='warning';return true;});try{unserialize('a:2:{i:0;O:12:\"RestoreParse\":0:{}i:1;');}catch(Throwable $e){$events[]=$e->getMessage();}restore_error_handler();return$events;",
                (env,result)->{assertEquals(3,result.toValue(ArrayMemory.class).size());assertEquals("warning",result.valueOfIndex(0).toString());
                    assertEquals("wake",result.valueOfIndex(1).toString());assertEquals("wake-stop",result.valueOfIndex(2).toString());});
    }
    @Test public void firstFailedQueuedHookSkipsLaterHooksWithoutFinalizingEarlierSuccess() throws Throwable {
        modes("$saved=[];$events=[];class RestoreSequence {public $id=0;function __wakeup(){global $saved,$events;$saved[]=$this;$events[]=$this->id;if($this->id===2)throw new Exception('stop');}"
                +"function __destruct(){}}try{unserialize('a:3:{i:0;O:15:\"RestoreSequence\":1:{s:2:\"id\";i:1;}i:1;O:15:\"RestoreSequence\":1:{s:2:\"id\";i:2;}i:2;O:15:\"RestoreSequence\":1:{s:2:\"id\";i:3;}}');}catch(Throwable $e){}return[$saved,$events];",
                (env,result)->{assertEquals(2,result.valueOfIndex(0).toValue(ArrayMemory.class).size());
                    assertFalse(object(result.valueOfIndex(0).valueOfIndex(0)).isFinalized());assertTrue(object(result.valueOfIndex(0).valueOfIndex(1)).isFinalized());
                    assertEquals(2,result.valueOfIndex(1).toValue(ArrayMemory.class).size());});
    }
    @Test public void successfulHookDoesNotSuppressLaterDestruction() throws Throwable {
        modes("$escaped=null;class RestoreSuccess {function __wakeup(){global $escaped;$escaped=$this;}function __destruct(){}}"
                +"unserialize('O:14:\"RestoreSuccess\":0:{}');return$escaped;",(env,result)->{
                    assertFalse(object(result).isFinalized());assertTrue(PhpObjectLifecycle.destruct(object(result),env,PhpObjectLifecycle.Reason.OWNERSHIP_RELEASE));});
    }
}
