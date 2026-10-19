package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;

/** Proposed emitter gate. Pinned oracle rows are separate from unpinned additional route controls. */
public class PhpNamedArgumentEmitterTest extends JvmCompilerCase {
    @Override protected php.runtime.env.CompileScope newScope() {
        var scope = super.newScope();
        scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        return scope;
    }

    private record Row(String name, boolean strict, String source, String expected) {}
    private static List<Row> rows(String resource) throws IOException {
        var stream = PhpNamedArgumentEmitterTest.class.getResourceAsStream("/resources/functions/" + resource);
        assertNotNull("Missing test resource " + resource, stream);
        var result = new ArrayList<Row>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var columns = line.split("\\t", -1);
                assertEquals("Malformed corpus row", 4, columns.length);
                result.add(new Row(columns[0], Boolean.parseBoolean(columns[1]),
                        new String(Base64.getDecoder().decode(columns[2]), StandardCharsets.UTF_8),
                        new String(Base64.getDecoder().decode(columns[3]), StandardCharsets.UTF_8)));
            }
        }
        return result;
    }

    private void check(Row row, boolean debug, boolean dumped) throws Throwable {
        check(row, debug, dumped, PhpLanguageProfile.PHP_8_5);
    }

    private void check(Row row, boolean debug, boolean dumped, PhpLanguageProfile profile) throws Throwable {
        var scope = newScope();
        scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
        var output = new ByteArrayOutputStream(); var env = new Environment(scope, output);
        try {
            var context = new Context(row.source()); context.setPhpLanguageProfile(profile);
            var module = new JvmCompiler(env, context).compile(false);
            if (dumped) {
                var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
                dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
            var bytecodes = new ArrayList<byte[]>(); bytecodes.add(module.getData());
            for (var function : module.getFunctions()) bytecodes.add(function.getData());
            for (var clazz : module.getClasses()) if (clazz.getData() != null) bytecodes.add(clazz.getData());
            for (var closure : module.getClosures()) bytecodes.add(closure.getData());
            for (var bytes : bytecodes) {
                var errors = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(bytes), scope.getClassLoader(), false, new PrintWriter(errors));
                assertEquals("ASM verifier " + row.name(), "", errors.toString());
            }
            if (row.name().equals("nested_constructor_argument_failure")) checkHandlerPriority(module.getData());
            module.include(env); env.doFinal();
            assertEquals(row.name() + " strict=" + row.strict() + " debug=" + debug + " PHB=" + dumped,
                    row.expected(), output.toString(StandardCharsets.UTF_8));
        } finally { env.doFinal(); scope.getClassLoader().close(); }
    }

    private static boolean frameCleanup(MethodNode method, TryCatchBlockNode handler) {
        if (!"java/lang/Throwable".equals(handler.type)) return false;
        int count = 0;
        for (AbstractInsnNode op = handler.handler.getNext(); op != null && count < 12; op = op.getNext()) {
            if (op.getOpcode() < 0) continue; count++;
            if (op instanceof MethodInsnNode call && call.owner.equals("php/runtime/invoke/PhpCallFrame")
                    && call.name.equals("fail") && call.desc.equals("(Ljava/lang/Throwable;)Ljava/lang/Throwable;")) return true;
            if (op.getOpcode() == org.objectweb.asm.Opcodes.ATHROW) return false;
        }
        return false;
    }

    /** JVM uses the first matching table entry, so assertions inspect the finalized MethodNode table. */
    private static void checkHandlerPriority(byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        int nestedPairs = 0, phpCatchPairs = 0;
        for (var method : node.methods) {
            for (int i = 0; i < method.tryCatchBlocks.size(); i++) {
                var inner = method.tryCatchBlocks.get(i);
                if (!frameCleanup(method, inner)) continue;
                int from = method.instructions.indexOf(inner.start), to = method.instructions.indexOf(inner.end);
                for (int j = 0; j < method.tryCatchBlocks.size(); j++) {
                    if (i == j) continue; var outer = method.tryCatchBlocks.get(j);
                    int outerFrom = method.instructions.indexOf(outer.start), outerTo = method.instructions.indexOf(outer.end);
                    if (outerFrom <= from && to <= outerTo && (outerFrom < from || to < outerTo)) {
                        if (frameCleanup(method, outer)) {
                            assertTrue("Inner frame cleanup follows outer in " + method.name, i < j); nestedPairs++;
                        } else if (outer.type == null || "php/runtime/lang/exception/BaseBaseException".equals(outer.type)) {
                            assertTrue("Frame cleanup follows enclosing PHP catch in " + method.name, i < j); phpCatchPairs++;
                        }
                    }
                }
            }
        }
        assertTrue("Fixture must actually emit nested protected frame ranges", nestedPairs > 0);
        assertTrue("Fixture must actually emit an enclosing PHP catch range", phpCatchPairs > 0);
    }

    private void corpus(String resource, int count, boolean... debugModes) throws Throwable {
        var cases = rows(resource); assertEquals("Corpus row count", count, cases.size());
        var failures = new ArrayList<String>();
        for (var row : cases) for (boolean debug : debugModes) for (boolean dumped : new boolean[]{false, true}) {
            try { check(row, debug, dumped); }
            catch (Throwable error) {
                failures.add(row.name() + " strict=" + row.strict() + " debug=" + debug + " PHB=" + dumped
                        + " " + error.getClass().getName() + ": " + error.getMessage());
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test(timeout = 120000) public void nativeNamedArgumentsOptimizedSourceAndDump() throws Throwable {
        corpus("php85_named_argument_emitter.tsv", 94, false);
    }
    @Test(timeout = 120000) public void nativeNamedArgumentsDebugSourceAndDump() throws Throwable {
        corpus("php85_named_argument_emitter.tsv", 94, true);
    }
    // Reasoned expectations only: parent must pin these five sources before describing them as native proof.
    @Test(timeout = 30000) public void additionalEmitterRoutesAndNestedCleanupSourceAndDump() throws Throwable {
        corpus("php85_named_argument_emitter_routes.tsv", 10, false, true);
    }
    @Test(timeout = 30000) public void positionalAndLegacyCompatibilitySourceAndDump() throws Throwable {
        var row = new Row("positional-compatibility", false,
                "function f(&$x,$y){$x+=$y;}$x=1;f($x,2);class C{public $v;function __construct($v){$this->v=$v;}}echo $x,',',(new C(5))->v;", "3,5");
        for (var profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true})
                check(row, debug, dumped, profile);
    }
}
