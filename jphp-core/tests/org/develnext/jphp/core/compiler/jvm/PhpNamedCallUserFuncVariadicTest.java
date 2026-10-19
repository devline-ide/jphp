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

/** Prospective staged controls; root owns compilation and native comparison. */
public class PhpNamedCallUserFuncVariadicTest {
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

    @Test public void mandatoryCorpusAndNamedCallbackUseTheRealPhpDeclaration() throws Throwable {
        check("function f($a,$b){return [$a,$b];}return json_encode(["
                + "call_user_func('f',b:2,a:1),call_user_func(b:4,callback:'f',a:3)]);",
                "[[1,2],[3,4]]");
    }

    @Test public void actualNamedVariadicKeysReachTheCallback() throws Throwable {
        check("function f(...$rest){return $rest;}return json_encode(call_user_func('f',args:3,z:4));",
                "{\"args\":3,\"z\":4}");
    }

    @Test public void nestedNativeInvocationKeepsEachTargetAndFuncGetArgs() throws Throwable {
        check("function f($a,$b){return [$a,$b];}"
                + "function g($x,$y){return [call_user_func('f',b:$y,a:$x),func_get_args()];}"
                + "return json_encode(call_user_func('g',y:2,x:1));", "[[1,2],[1,2]]");
    }

    @Test public void callUserFuncUsesValueTemporariesAndInternalWeakCoercion() throws Throwable {
        check("$w=[];set_error_handler(function($n,$s)use(&$w){$w[]=$s;return true;});"
                + "function f(&$a){$a=9;return $a;}function typed(int $a){return $a;}"
                + "$x=1;$v=call_user_func('f',a:$x);restore_error_handler();"
                + "return json_encode([$v,$x,count($w),strpos($w[0],'must be passed by reference')!==false,"
                + "call_user_func('typed',a:'7')]);", "[9,1,1,true,7]");
    }

    @Test public void duplicateCallbackRejectsBeforeTheCallbackBody() throws Throwable {
        check("$effects=0;function f(){global $effects;$effects++;}"
                + "try{call_user_func('f',callback:'f');return 'miss';}"
                + "catch(Error $e){return json_encode([$effects,$e->getMessage()]);}",
                "[0,\"Named parameter $callback overwrites previous argument\"]");
    }
}
