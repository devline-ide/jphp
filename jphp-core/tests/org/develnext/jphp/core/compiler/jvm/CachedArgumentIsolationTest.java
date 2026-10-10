package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class CachedArgumentIsolationTest extends JvmCompilerCase {
    @Test public void repeatedCallsPreserveArgumentsAndReferences() throws Throwable {
        String source;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/functions/cached_argument_isolation.php")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8).substring(5);
        }
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().registerExtension(new StandardExtension());
            try {
                Context context = new Context(source);
                context.setPhpLanguageProfile(profile);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);
                    var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                var diagnostics = new StringWriter();
                var writer = new PrintWriter(diagnostics);
                var loader = env.getScope().getClassLoader();
                CheckClassAdapter.verify(new ClassReader(module.getData()), loader, false, writer);
                for (var clazz : module.getClasses()) CheckClassAdapter.verify(new ClassReader(clazz.getData()), loader, false, writer);
                for (var closure : module.getClosures()) CheckClassAdapter.verify(new ClassReader(closure.getData()), loader, false, writer);
                assertEquals(profile + "/" + dumped, "", diagnostics.toString());
                assertEquals(profile + "/" + dumped, "arguments-preserved", module.include(env).toString());
            } finally { env.doFinal(); }
        }
    }
}
