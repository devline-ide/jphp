package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import static org.junit.Assert.*;

/** Real class verification and PHP capture semantics through SOURCE and PHB, without PCRE. */
public class PhpClosureControlFlowTest {
    private static String resource(String name) throws Exception {
        String resource = "resources/native-oracle/closure-cfg/" + name + ".php";
        try (var input = PhpClosureControlFlowTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull("Missing closure control-flow resource " + resource, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).substring(5);
        }
    }

    private static void exercise(String name) throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            var scope = new CompileScope();
            scope.setDebugMode(debug);
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new CoreExtension());
            scope.registerExtension(new SPLExtension());
            var output = new ByteArrayOutputStream();
            var env = new Environment(scope, output);
            try {
                var context = new Context(resource(name));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                ModuleEntity module = new JvmCompiler(env, context).compile(false);
                if (phb) {
                    var bytes = new ByteArrayOutputStream();
                    var dumper = new ModuleDumper(context, env, debug);
                    dumper.save(module, bytes);
                    assertTrue("Actual PHB bytes required", bytes.size() > 0);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    assertTrue("Actual loaded PHB artifact required", module.isCompiledArtifact());
                }
                scope.loadModule(module);
                scope.addUserModule(module);
                env.registerModule(module);
                Memory result = module.include(env);
                assertTrue(name + " debug=" + debug + " phb=" + phb, result.toValue().getRealType() == Memory.Type.BOOL && result.toBoolean());
            } finally {
                env.doFinal();
            }
        }
    }

    @Test(timeout=60000) public void consecutiveImplicitReceiversPreserveValueAndReferenceCaptures() throws Throwable { exercise("consecutive"); }
    @Test(timeout=60000) public void shortCircuitArmsDoNotPublishUninitializedReceiverLocals() throws Throwable { exercise("short-circuit"); }
    @Test(timeout=60000) public void nestedClosuresRemainValidAfterSkippedConditionalProducer() throws Throwable { exercise("nested"); }
}
