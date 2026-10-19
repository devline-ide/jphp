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

/** Test-only supplement: internal weak coercion across resolved callback routes. */
public class PhpNamedInternalWeakRouteTest {
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

    @Test public void nativeCallbacksUseInternalWeakWithNamedArguments() throws Throwable {
        check("return json_encode([call_user_func('str_repeat',times:'2',string:7),call_user_func('substr',length:'2',offset:'1',string:1234)]);",
                "[\"77\",\"23\"]");
    }

    @Test public void closureStaticAndInstanceCallbacksUseInternalWeakAndDeclarationOrder() throws Throwable {
        check("$closure=function(int $a,int $b){return [$a,$b,func_get_args()];};class RouteTargets{static function sm(int $a,int $b){return [$a,$b,func_get_args()];}function im(int $a,int $b){return [$a,$b,func_get_args()];}}$object=new RouteTargets;return json_encode([call_user_func($closure,b:'2',a:'7'),call_user_func(['RouteTargets','sm'],b:'2',a:'7'),call_user_func([$object,'im'],b:'2',a:'7')]);",
                "[[7,2,[7,2]],[7,2,[7,2]],[7,2,[7,2]]]");
    }

}
