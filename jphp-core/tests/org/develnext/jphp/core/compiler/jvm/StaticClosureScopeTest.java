package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.lang.exception.BaseBaseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StaticClosureScopeTest extends JvmCompilerCase {
    private String evaluate(String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Environment env = new Environment(newScope(), new ByteArrayOutputStream());
        try {
            Context context = new Context(profile == PhpLanguageProfile.JPHP_LEGACY
                    ? source.replace("return static function", "return function") : source);
            context.setPhpLanguageProfile(profile);
            var module = new JvmCompiler(env, context).compile(false);
            if (dumped) {
                var dumper = new ModuleDumper(context, env, true);
                var bytes = new ByteArrayOutputStream();
                dumper.save(module, bytes);
                module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
            return module.include(env).toString();
        } finally { env.doFinal(); }
    }

    @Test public void retainedStaticClosuresKeepTheirPrivateScope() throws Throwable {
        String source = """
            class ScopeOwner {
                private static function secret() { return 'owned'; }
                static function callback() { return function () { return self::secret(); }; }
                static function staticCallback() { return static function () { return self::secret(); }; }
            }
            class ForeignCaller { static function invoke($callback) { return $callback(); } }
            $first = ScopeOwner::callback(); $second = ScopeOwner::staticCallback();
            for ($i = 0; $i < 3; $i++) {
                if (ForeignCaller::invoke($first) !== 'owned' || ForeignCaller::invoke($second) !== 'owned') return 'lost-scope';
            }
            return 'scope-preserved';
            """;
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dumped : new boolean[] { false, true })
            assertEquals(profile + "/" + dumped, "scope-preserved", evaluate(source, profile, dumped));
    }

    @Test public void foreignStaticClosuresCannotReadPrivateMethods() throws Throwable {
        String source = """
            class ScopeOwner { private static function secret() { return 'owned'; } }
            class ForeignCaller { static function callback() { return static function () { return ScopeOwner::secret(); }; } }
            $callback = ForeignCaller::callback(); return $callback();
            """;
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dumped : new boolean[] { false, true }) {
            try { evaluate(source, profile, dumped); fail("Foreign closure acquired private scope: " + profile + "/" + dumped); }
            catch (Exception failure) {
                String message = failure instanceof BaseBaseException phpFailure
                        ? phpFailure.getMessage(phpFailure.getEnvironment()).toString() : failure.getMessage();
                assertTrue(String.valueOf(message), message != null && message.contains("private method"));
            }
        }
    }
}
