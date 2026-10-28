package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.invoke.InvokeArgumentHelper;
import php.runtime.lang.Closure;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;

public class ClosureDisplayNameIntegrationTest extends JvmCompilerCase {
    @Test public void capturedNamespacedFunctionAndStaticMethodKeepTheirQualifiedCallNames() throws Throwable {
        String source = "namespace ClosureNames;function calculate($value){return $value;}"
                + "class Box {public static function compute($value){return $value;}}"
                + "return [calculate(...),Box::compute(...),function($value){return $value;}];";
        for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope(); var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                var context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);
                    var bytes = new ByteArrayOutputStream(); dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                var result = module.include(env);
                var function = (Closure) result.valueOfIndex(null, 0).toValue(ObjectMemory.class).value;
                var method = (Closure) result.valueOfIndex(null, 1).toValue(ObjectMemory.class).value;
                var ordinary = (Closure) result.valueOfIndex(null, 2).toValue(ObjectMemory.class).value;
                for (int i = 0; i < 3; i++) {
                    InvokeArgumentHelper.closureCallName(ordinary);
                    assertEquals("ClosureNames\\calculate", InvokeArgumentHelper.closureCallName(function));
                    assertEquals("ClosureNames\\Box::compute", InvokeArgumentHelper.closureCallName(method));
                }
            } finally { env.doFinal(); }
        }
    }
}
