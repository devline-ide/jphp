package standard;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.zend.ext.standard.StandardExtension;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class Php85StringStepsTest {
    private void verify(String group) throws Throwable {
        int fixtures = 0;
        try (var resource = getClass().getResourceAsStream("/standard/php85_string_steps.tsv")) {
            assertNotNull(resource);
            for (String line : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] row = line.split("\\t", -1);
                assertEquals(5, row.length);
                if (!row[0].equals(group)) continue;
                fixtures++;
                String source = new String(Base64.getDecoder().decode(row[3]), StandardCharsets.UTF_8);
                String expected = new String(Base64.getDecoder().decode(row[4]), StandardCharsets.UTF_8);
                for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
                    var scope = new CompileScope();
                    scope.registerExtension(new CoreExtension());
                    scope.registerExtension(new SPLExtension());
                    scope.registerExtension(new StandardExtension());
                    scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    scope.setDebugMode(debug);
                    var env = new Environment(scope, new ByteArrayOutputStream());
                    try {
                        var context = new Context(source);
                        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module = new JvmCompiler(env, context).compile(false);
                        if (dumped) {
                            var dumper = new ModuleDumper(context, env, true);
                            var output = new ByteArrayOutputStream();
                            dumper.save(module, output);
                            module = dumper.load(new ByteArrayInputStream(output.toByteArray()));
                        }
                        scope.loadModule(module);
                        scope.addUserModule(module);
                        env.registerModule(module);
                        String actual = module.include(env).toString();
                        int mismatch = 0;
                        while (mismatch < Math.min(expected.length(), actual.length())
                                && expected.charAt(mismatch) == actual.charAt(mismatch)) mismatch++;
                        assertTrue(row[1] + " debug=" + debug + " dump=" + dumped + " first difference=" + mismatch
                                + " expected=" + expected.substring(mismatch, Math.min(mismatch + 180, expected.length()))
                                + " actual=" + actual.substring(mismatch, Math.min(mismatch + 180, actual.length())), expected.equals(actual));
                    } finally { env.doFinal(); }
                }
            }
        }
        assertEquals("weak and strict fixtures", 2, fixtures);
    }

    @Test public void everyAsciiDigitAndMixedCarryBorrow() throws Throwable { verify("digits"); }
    @Test public void emptyNonAsciiAndInvalidBytes() throws Throwable { verify("invalid"); }
    @Test public void weakStrictTypesAndNullDeprecation() throws Throwable { verify("types"); }
    @Test public void exactArityErrors() throws Throwable { verify("arity"); }
    @Test public void dynamicAndCallbackRoutes() throws Throwable { verify("routes"); }
    @Test public void declarationReflection() throws Throwable { verify("reflection"); }
    @Test public void originalValueAndReferenceAreUnchanged() throws Throwable { verify("unchanged"); }
    @Test public void stringableAndOriginalThrownIdentity() throws Throwable { verify("stringable"); }
}
