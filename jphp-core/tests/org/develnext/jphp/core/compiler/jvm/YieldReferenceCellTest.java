package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.stmt.FunctionStmtToken;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Consumer writes through a yielded local must invalidate its cached scalar value. */
public class YieldReferenceCellTest extends JvmCompilerCase {
    private String resource(String suffix) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/generators/php_yield_reference_cell." + suffix)) {
            assertNotNull(input); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private void execute(boolean dumped) throws Throwable {
        String source = resource("php"), expected = resource("expected"); List<String> failures = new ArrayList<>();
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false,true}) {
            var scope = newScope(); scope.setDebugMode(debug); ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(scope, output);
            try {
                Context context = new Context(source.substring(5)); context.setPhpLanguageProfile(profile);
                ModuleEntity module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    ModuleDumper dumper = new ModuleDumper(context, env, true); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                module.include(env); env.getDefaultBuffer().flush(); assertEquals(expected, output.toString(StandardCharsets.UTF_8));
            } catch (Throwable failure) { failures.add(profile + " debug=" + debug + " dumped=" + dumped + " " + failure); }
            finally { env.doFinal(); }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }
    @Test public void consumerMutationsReachYieldedLocalFromSource() throws Throwable { execute(false); }
    @Test public void consumerMutationsReachYieldedLocalFromPhb() throws Throwable { execute(true); }
    @Test public void onlySimpleByrefYieldReservesReferenceLocalAndDisablesConstantCache() throws Throwable {
        String source = "function &actual_cell(){ $cell=1; yield $cell; return 0; } "
                + "function value_cell(){ $cell=1; yield $cell; return 0; } "
                + "function &expression_cell(){ $cell=1; yield $cell+1; return 0; } "
                + "function &empty_cell(){ $cell=1; yield; return 0; }";
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            var scope = newScope(); Environment env = new Environment(scope, new ByteArrayOutputStream());
            try {
                Context context = new Context(source); context.setPhpLanguageProfile(profile);
                SyntaxAnalyzer analyzer = new SyntaxAnalyzer(env, new Tokenizer(context)); int checked = 0;
                for (FunctionStmtToken function : analyzer.getFunctions()) {
                    boolean actual = function.getName().getName().equals("actual_cell");
                    var stats = function.variable("cell");
                    assertEquals(function.getName().getName(), actual, stats.isReference());
                    assertEquals(function.getName().getName(), actual, stats.isUnstable());
                    if (actual) assertTrue(stats.isMutable());
                    assertFalse("no blanket dynamic locals", function.isDynamicLocal()); checked++;
                }
                assertEquals(4, checked);
            } finally { env.doFinal(); }
        }
    }
}
