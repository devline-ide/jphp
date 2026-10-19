package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.loader.dump.ModuleDumper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.assertEquals;

/** Test-only supplement: real CUF reflection failure and subsequent bound invocations. */
public class PhpNamedNativeCleanupTest {
    private void check(String source, String expected) throws Throwable {
        for (boolean strict : new boolean[]{false, true})
            for (boolean debug : new boolean[]{false, true})
                for (boolean dumped : new boolean[]{false, true}) {
                    var scope = new CompileScope();
                    scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
                    scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                    scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                    scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var env = new Environment(scope, new ByteArrayOutputStream());
                    try {
                        var context = new Context((strict ? "declare(strict_types=1);" : "") + source);
                        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module = new JvmCompiler(env, context).compile(false);
                        if (dumped) {
                            var dumper = new ModuleDumper(context, env, true);var data = new ByteArrayOutputStream();
                            dumper.save(module, data);module = dumper.load(new ByteArrayInputStream(data.toByteArray()));
                        }
                        scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                        assertEquals("strict=" + strict + " debug=" + debug + " dump=" + dumped,
                                expected, module.include(env).toString());
                    } finally {try {env.doFinal();} finally {scope.getClassLoader().close();}}
                }
    }

    @Test public void directErrorThenNamedCallUsesSameEnvironment() throws Throwable {
        check("function fail($value){throw new Error('stop:'.$value);}function pair($a,$b){return [func_get_args(),$a,$b];}$caught=[];try{call_user_func('fail',value:3);}catch(Error $e){$caught[]=$e->getMessage();}return json_encode([$caught,call_user_func('pair',b:2,a:1)]);",
                "[[\"stop:3\"],[[1,2],1,2]]");
    }

    @Test public void nestedCaughtErrorRestoresOuterAndNextNativeTail() throws Throwable {
        check("function fail($value){throw new Error('nested:'.$value);}function pair($a,$b){return [$a,$b];}function outer($a,$b){$before=func_get_args();try{call_user_func('fail',value:$a);}catch(Error $e){$caught=$e->getMessage();}$after=func_get_args();$recovered=call_user_func('pair',b:$b,a:$a);return [$before,$caught,$after,$recovered];}$nested=call_user_func('outer',b:2,a:1);return json_encode([$nested,call_user_func('pair',b:4,a:3)]);",
                "[[[1,2],\"nested:1\",[1,2],[1,2]],[3,4]]");
    }

    @Test public void nestedUncaughtErrorClosesBothReflectedInvocations() throws Throwable {
        check("function inner($value){throw new Error('through:'.$value);}function outer($a){return call_user_func('inner',value:$a);}function pair($a,$b){return [func_get_args(),$a,$b];}$caught=[];try{call_user_func('outer',a:3);}catch(Error $e){$caught[]=$e->getMessage();}return json_encode([$caught,call_user_func('pair',b:2,a:1)]);",
                "[[\"through:3\"],[[1,2],1,2]]");
    }

    @Test public void failureAndNextCallKeepAliasesValueOnlyAndInternalWeak() throws Throwable {
        check("$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=$s;return true;});function fail(&$value){$value=99;throw new Error('stop');}function succeed(&$value,int $other){$value=$other;return [func_get_args(),$value];}$x=1;$alias=&$x;try{call_user_func('fail',value:$alias);}catch(Error $e){$caught=$e->getMessage();}$next=call_user_func('succeed',other:'7',value:$alias);restore_error_handler();return json_encode([$caught,$x,$alias,count($warnings),strpos($warnings[0],'must be passed by reference')!==false,strpos($warnings[1],'must be passed by reference')!==false,$next]);",
                "[\"stop\",1,1,2,true,true,[[7,7],7]]");
    }
}
