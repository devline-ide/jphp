package standard;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.zend.ext.standard.StandardExtension;
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

final class StandardFunctionTestSupport {
    static void assertResult(String source, String expected) throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values())
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var scope = new CompileScope();
                scope.registerExtension(new CoreExtension());
                scope.registerExtension(new SPLExtension());
                scope.registerExtension(new StandardExtension());
                scope.setPhpLanguageProfile(profile);
                scope.setDebugMode(debug);
                var env = new Environment(scope, new ByteArrayOutputStream());
                try {
                    var context = new Context(source);
                    context.setPhpLanguageProfile(profile);
                    var module = new JvmCompiler(env, context).compile(false);
                    if (phb) {
                        var dumper = new ModuleDumper(context, env, true);
                        var output = new ByteArrayOutputStream();
                        dumper.save(module, output);
                        module = dumper.load(new ByteArrayInputStream(output.toByteArray()));
                    }
                    scope.loadModule(module);
                    scope.addUserModule(module);
                    env.registerModule(module);
                    assertEquals(profile + " debug=" + debug + " phb=" + phb, expected, module.include(env).toString());
                } finally { env.doFinal(); scope.getClassLoader().close(); }
            }
    }
}
