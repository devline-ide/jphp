package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;

/** The extended oracle resource must be pinned by the parent before this proposed test runs. */
public class PhpNativeNamedFixedSignatureTest extends JvmCompilerCase {
    @Override protected php.runtime.env.CompileScope newScope() {
        var scope = super.newScope();
        scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        return scope;
    }

    private record Row(String name, String source, String output) {}

    private List<Row> rows(String resource, int expectedCount) throws IOException {
        var stream = getClass().getResourceAsStream("/resources/functions/" + resource);
        assertNotNull("Pin native controls and copy the resulting resource before running", stream);
        var rows = new ArrayList<Row>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var c = line.split("\\t", -1); assertEquals(4, c.length);
                rows.add(new Row(c[0] + " strict=" + c[1],
                        new String(Base64.getDecoder().decode(c[2]), StandardCharsets.UTF_8),
                        new String(Base64.getDecoder().decode(c[3]), StandardCharsets.UTF_8)));
            }
        }
        assertEquals(expectedCount, rows.size()); return rows;
    }

    private void check(Row row, PhpLanguageProfile profile, boolean debug, boolean phb) throws Throwable {
        var scope = newScope(); scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
        var out = new ByteArrayOutputStream(); var env = new Environment(scope, out);
        try {
            var context = new Context(row.source()); context.setPhpLanguageProfile(profile);
            var module = new JvmCompiler(env, context).compile(false);
            if (phb) {
                var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
                dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
            var classes = new ArrayList<byte[]>(); classes.add(module.getData());
            for (var fn : module.getFunctions()) classes.add(fn.getData());
            for (var closure : module.getClosures()) classes.add(closure.getData());
            for (var clazz : module.getClasses()) if (clazz.getData() != null) classes.add(clazz.getData());
            for (var bytes : classes) {
                var errors = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(bytes), scope.getClassLoader(), false, new PrintWriter(errors));
                assertEquals(row.name() + " verifier", "", errors.toString());
            }
            module.include(env); env.doFinal();
            assertEquals(row.name() + " debug=" + debug + " PHB=" + phb, row.output(), out.toString(StandardCharsets.UTF_8));
        } finally {
            try { env.doFinal(); } finally { scope.getClassLoader().close(); }
        }
    }

    private void corpus(String resource, int count) throws Throwable {
        var errors = new ArrayList<String>();
        for (var row : rows(resource, count)) for (boolean debug : new boolean[]{false, true})
            for (boolean phb : new boolean[]{false, true}) {
                try { check(row, PhpLanguageProfile.PHP_8_5, debug, phb); }
                catch (Throwable failure) { errors.add(row.name() + " debug=" + debug + " PHB=" + phb + " " + failure); }
            }
        assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 60000) public void unchangedPinnedNamedNativeRowsSourceAndDump() throws Throwable {
        corpus("php85_native_named_fixed_pinned.tsv", 8);
    }

    @Test(timeout = 120000) public void newPinnedScalarMetadataAndCoercionControlsSourceAndDump() throws Throwable {
        corpus("php85_native_named_fixed_controls.tsv", 56);
    }

    @Test(timeout = 30000) public void legacyPositionalBodiesAndOldJavaDescriptorsRemainAvailable() throws Throwable {
        var strings = org.develnext.jphp.zend.ext.standard.StringFunctions.class;
        assertEquals(php.runtime.Memory.class, strings.getDeclaredMethod("substr", String.class, int.class, int.class).getReturnType());
        assertNotNull(strings.getDeclaredMethod("substr", String.class, int.class).getAnnotation(php.runtime.annotation.Reflection.Ignore.class));
        assertEquals(String.class, strings.getDeclaredMethod("str_repeat", String.class, int.class).getReturnType());
        assertEquals(boolean.class, org.develnext.jphp.zend.ext.standard.ArrayFunctions.class.getDeclaredMethod("sort",
                Environment.class, php.runtime.env.TraceInfo.class, php.runtime.Memory.class, int.class).getReturnType());
        var row = new Row("legacy fixed native", "echo substr('abcd',1),';',substr('abcd',1,null),';',str_repeat('x',-1);", "bcd;;");
        for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true})
            check(row, PhpLanguageProfile.JPHP_LEGACY, debug, phb);
    }
}
