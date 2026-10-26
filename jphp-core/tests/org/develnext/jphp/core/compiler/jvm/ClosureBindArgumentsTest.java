package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;

public class ClosureBindArgumentsTest extends JvmCompilerCase {
    private void verify(String source, String expected) throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values())
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var scope = newScope();
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

    @Test public void bindsTheRequestedObjectAndPrivateScopeWithoutChangingOriginal() throws Throwable {
        verify("class Box { private $value = 'secret'; public function read() { return $this->value; } }"
                + "$target = new Box(); $original = function &() { return $this->value; };"
                + "$bound = Closure::bind($original, $target, Box::class);"
                + "$alias =& $bound(); $alias = 'changed';"
                + "return $target->read() . '|' . ($bound === $original ? 'same' : 'new');", "changed|new");
    }

    @Test public void omittedScopeAndNullReceiverRemainCallable() throws Throwable {
        verify("$original = function() { return 'ok'; };"
                + "$bound = Closure::bind($original, null); return $bound();", "ok");
    }
}
