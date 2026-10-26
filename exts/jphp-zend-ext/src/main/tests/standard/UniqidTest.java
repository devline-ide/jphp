package standard;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.develnext.jphp.zend.ext.standard.StringFunctions;
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
import java.util.HashSet;
import java.util.Locale;

import static org.junit.Assert.*;

public class UniqidTest {
    @Test public void timestampFormatAndRepeatedCalls() {
        long before = System.currentTimeMillis() / 1000;
        var seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String id = StringFunctions.uniqid();
            assertTrue(id, id.matches("[0-9a-f]{13}"));
            assertTrue("duplicate " + id, seen.add(id));
            assertTrue(Long.parseLong(id.substring(8), 16) < 1_000_000);
            long seconds = Long.parseLong(id.substring(0, 8), 16);
            assertTrue(seconds >= before && seconds <= System.currentTimeMillis() / 1000 + 1);
        }
    }

    @Test public void prefixAndEntropyAreLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertTrue(StringFunctions.uniqid("item_", false).matches("item_[0-9a-f]{13}"));
            assertTrue(StringFunctions.uniqid("item_", true).matches("item_[0-9a-f]{14}\\.[0-9]{8}"));
        } finally { Locale.setDefault(previous); }
    }

    @Test public void phpCallsInBothProfilesAndSourceAndPhb() throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean phb : new boolean[]{false, true}) {
            var scope = new CompileScope();
            scope.registerExtension(new CoreExtension());
            scope.registerExtension(new SPLExtension());
            scope.registerExtension(new StandardExtension());
            scope.setPhpLanguageProfile(profile);
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                var context = new Context("return uniqid() . '|' . uniqid('prefix_') . '|' . uniqid('prefix_', true);");
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
                String[] values = module.include(env).toString().split("\\|");
                assertEquals(3, values.length);
                assertTrue(values[0], values[0].matches("[0-9a-f]{13}"));
                assertTrue(values[1], values[1].matches("prefix_[0-9a-f]{13}"));
                assertTrue(values[2], values[2].matches("prefix_[0-9a-f]{14}\\.[0-9]{8}"));
            } finally { env.doFinal(); scope.getClassLoader().close(); }
        }
    }
}
