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

/** Staged integration controls; all PHP sources already use supported keyed-array syntax. */
public class PhpNamedIndirectArgumentTest {
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

    @Test public void indirectFixedHolesAndNamedVariadicKeepCallArguments() throws Throwable {
        check("function f($a=1,$b=2,...$rest){return [func_num_args(),func_get_args(),$rest];}"
                + "return json_encode(call_user_func_array('f',['b'=>8,'z'=>9]));",
                "[2,[1,8],{\"z\":9}]");
    }

    @Test public void capturedUserTargetIsPreparedOnce() throws Throwable {
        check("function f($a=1,$b=2,...$rest){return [func_get_args(),$rest];}$c=f(...);"
                + "return json_encode(call_user_func_array($c,['b'=>8,'z'=>9]));",
                "[[1,8],{\"z\":9}]");
    }

    @Test public void indirectReferenceArrayPreservesTheActualAlias() throws Throwable {
        check("function f(&$a,$b){$a=$b;}$x=1;call_user_func_array('f',['b'=>7,'a'=>&$x]);"
                + "return json_encode($x);", "7");
    }

    @Test public void reflectionUsesDeclarationOrderAndKeyedConstructorArguments() throws Throwable {
        check("function f($a,$b){return [$a,$b];}class C{public $v;function __construct($a,$b){$this->v=[$a,$b];}"
                + "function m($a,$b){return [$a,$b];}}$o=new C(0,0);"
                + "return json_encode([(new ReflectionFunction('f'))->invokeArgs(['b'=>2,'a'=>1]),"
                + "(new ReflectionMethod('C','m'))->invokeArgs($o,['b'=>2,'a'=>1]),"
                + "(new ReflectionClass('C'))->newInstanceArgs(['b'=>2,'a'=>1])->v]);",
                "[[1,2],[1,2],[1,2]]");
    }

    @Test public void magicAndCapturedMagicKeepStringKeys() throws Throwable {
        check("class C{function __call($n,$a){return [$n,$a];}static function __callStatic($n,$a){return [$n,$a];}}"
                + "$o=new C;$captured=$o->missing(...);"
                + "return json_encode([call_user_func_array([$o,'missing'],['b'=>2,'a'=>1]),"
                + "call_user_func_array(['C','missing'],['z'=>9]),"
                + "call_user_func_array($captured,['b'=>2,'a'=>1])]);",
                "[[\"missing\",{\"b\":2,\"a\":1}],[\"missing\",{\"z\":9}],"
                        + "[\"missing\",{\"b\":2,\"a\":1}]]");
    }

    @Test public void explicitNativePhpDeclarationWorksThroughJavaVariadicTransport() throws Throwable {
        check("$c=str_contains(...);return json_encode(["
                + "call_user_func_array('str_contains',['needle'=>'b','haystack'=>'abc']),"
                + "call_user_func_array($c,['needle'=>'b','haystack'=>'abc'])]);", "[true,true]");
    }
}
