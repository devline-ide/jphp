package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.ParseException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.output.serialization.PhpSerialization;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.support.Entity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Exact native PHP 8.5.11 values, error classes/messages and ordered diagnostics. */
public class PhpDynamicPropertiesTest extends JvmCompilerCase {
    private String resource(String name, String suffix) throws IOException {
        String path = "resources/type_hinting/" + name + "." + suffix;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ModuleEntity compile(Environment env, String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Context context = new Context(source);
        context.setPhpLanguageProfile(profile);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module);
        env.getScope().addUserModule(module);
        env.registerModule(module);
        ArrayList<Entity> entities = new ArrayList<>();
        entities.add(module);
        entities.addAll(module.getClasses());
        entities.addAll(module.getFunctions());
        entities.addAll(module.getClosures());
        entities.addAll(module.getGenerators());
        StringWriter diagnostics = new StringWriter();
        PrintWriter writer = new PrintWriter(diagnostics);
        for (Entity entity : entities) if (entity.getData() != null && entity.getData().length != 0) {
            CheckClassAdapter.verify(new ClassReader(entity.getData()), env.getScope().getClassLoader(), false, writer);
        }
        assertEquals("ASM profile=" + profile + " dump=" + dumped, "", diagnostics.toString());
        return module;
    }

    private String wire(Environment env, TraceInfo trace, Memory value) throws Throwable {
        return PhpSerialization.serialize(env, trace, value).toString();
    }

    private void verifyFixture(String fixture, int count, boolean debug) throws Throwable {
        ArrayList<String> differences = new ArrayList<>();
        for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();
            scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Environment env = new Environment(scope, output);
            try {
                String source = (strict ? "declare(strict_types=1);" : "") + resource(fixture, "php").substring(5);
                ModuleEntity module = compile(env, source, PhpLanguageProfile.PHP_8_5, dumped);
                // The declaration/caller module must carry the contract independently of the Scope default.
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                ArrayMemory actual = module.include(env).toValue(ArrayMemory.class);
                TraceInfo trace = TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
                ArrayMemory expected = PhpSerialization.unserialize(env, trace,
                        StringMemory.valueOf(resource(fixture, "expected"))).toValue(ArrayMemory.class);
                String mode = "debug=" + debug + " strict=" + strict + " dump=" + dumped;
                assertEquals(mode, count, actual.size());
                assertEquals(mode, count, expected.size());
                for (int row = 0; row < count; row++) {
                    String wanted = wire(env, trace, expected.valueOfIndex(row));
                    String got = wire(env, trace, actual.valueOfIndex(row));
                    if (!wanted.equals(got)) differences.add(mode + " row=" + row + " expected=" + wanted + " actual=" + got);
                }
                env.getDefaultBuffer().flush();
                assertEquals("All diagnostics must be captured in rows: " + mode, "", output.toString(StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        }
        assertEquals(String.join("\n", differences), 0, differences.size());
    }

    @Test public void testModernDynamicPropertiesWithDynamicLocals() throws Throwable {
        verifyFixture("php85_dynamic_properties", 63, true);
    }

    @Test public void testModernDynamicPropertiesWithOptimizedLocals() throws Throwable {
        verifyFixture("php85_dynamic_properties", 63, false);
    }

    /** Kept separate: native reentrant writes can expose duplicate raw buckets and warmed-cache identity. */
    private void verifyNativeBoundary(String fixture, int rows) throws Throwable {
        ArrayList<String> failures = new ArrayList<>();
        for (boolean debug : new boolean[]{false, true}) {
            try { verifyFixture(fixture, rows, debug); }
            catch (AssertionError failure) { failures.add(failure.getMessage()); }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test public void testNativeReentrantDynamicPropertyBuckets() throws Throwable {
        verifyNativeBoundary("php85_dynamic_properties_reentrant", 7);
    }

    @Test public void testNativeDeclaredSlotOrderAndFailedRestorationLifetime() throws Throwable {
        verifyNativeBoundary("php85_dynamic_properties_storage", 2);
    }

    @Test public void testNativeDynamicPropertyIncrementDecrement() throws Throwable {
        verifyNativeBoundary("php85_dynamic_properties_increment", 4);
    }

    @Test public void testLegacyCreationKeepsZeroDeprecations() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            Environment env = new Environment(scope, new ByteArrayOutputStream());
            try {
                ModuleEntity module = compile(env, "class DPLegacy {} $events=[];"
                        + "set_error_handler(function($s,$m)use(&$events){$events[]=[$s,$m];return true;});"
                        + "$o=new DPLegacy;$o->x=1;$o->x=2;unset($o->x);$o->x=0;$r=&$o->x;$r=3;"
                        + "$o->a=[];$o->a[]=4;$o->b=5;restore_error_handler();return [$events,$o->x,$o->a,$o->b];",
                        PhpLanguageProfile.JPHP_LEGACY, dumped);
                Memory result = module.include(env);
                TraceInfo trace = TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
                ArrayMemory events = result.valueOfIndex(0).toValue(ArrayMemory.class);
                assertEquals("Legacy events=" + wire(env, trace, events), 0, events.size());
                assertEquals(3, result.valueOfIndex(1).toLong());
                assertEquals(4, result.valueOfIndex(2).valueOfIndex(0).toLong());
                assertEquals(5, result.valueOfIndex(3).toLong());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testMixedDeclarationAndCallerProfilesIncludingLegacyRestoration() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) for (PhpLanguageProfile declaration : PhpLanguageProfile.values())
                for (PhpLanguageProfile caller : PhpLanguageProfile.values()) {
            var scope = newScope();
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            Environment env = new Environment(scope, new ByteArrayOutputStream());
            try {
                compile(env, "class DPMixed {}", declaration, dumped).include(env);
                ModuleEntity module = compile(env, "$events=[];set_error_handler(function($s,$m)use(&$events){"
                        + "$events[]=[$s,$m];return true;});$o=new DPMixed;$o->x=1;$o->y=null;$r=&$o->y;$r=2;"
                        + "$restored=unserialize('O:7:\"DPMixed\":1:{s:1:\"z\";i:3;}');"
                        + "restore_error_handler();return [$events,$o->x,$o->y,$restored->z];", caller, dumped);
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                Memory result = module.include(env);
                ArrayMemory events = result.valueOfIndex(0).toValue(ArrayMemory.class);
                boolean modern = declaration == PhpLanguageProfile.PHP_8_5 || caller == PhpLanguageProfile.PHP_8_5;
                TraceInfo trace = TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
                assertEquals("declaration=" + declaration + " caller=" + caller + " dump=" + dumped
                        + " events=" + wire(env, trace, events), modern ? 3 : 0, events.size());
                if (modern) for (int i = 0; i < 3; i++) {
                    assertEquals(8192, events.valueOfIndex(i).valueOfIndex(0).toLong());
                    assertEquals("Creation of dynamic property DPMixed::$" + new String[]{"x", "y", "z"}[i]
                            + " is deprecated", events.valueOfIndex(i).valueOfIndex(1).toString());
                }
                assertEquals(1, result.valueOfIndex(1).toLong());
                assertEquals(2, result.valueOfIndex(2).toLong());
                assertEquals(3, result.valueOfIndex(3).toLong());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testDynamicPropertyNameParametersRemainUsedInAstAndOptimizedCode() throws Throwable {
        Context astContext = new Context("function field($o,$key){return $o->$key;}");
        astContext.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var ast = new org.develnext.jphp.core.syntax.SyntaxAnalyzer(null,
                new org.develnext.jphp.core.tokenizer.Tokenizer(astContext));
        assertNull(ast.getEnvironment());
        var function = ast.getFunctions().iterator().next();
        assertTrue(function.variable(org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken.valueOf("key")).isUsed());
        var returned = (org.develnext.jphp.core.tokenizer.token.stmt.ReturnStmtToken)
                function.getBody().getInstructions().getFirst().getSingle();
        assertNull(returned.getValue().getAsmExpr());
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope(); scope.setDebugMode(debug);
            Environment env = new Environment(scope, new ByteArrayOutputStream());
            try {
                ModuleEntity module = compile(env,
                        "function fieldSet($o,$key,$v){$o->$key=$v;} function fieldRead($o,$key){return $o->$key;}"
                        + "function fieldIsset($o,$key){return isset($o->$key);} function fieldUnset($o,$key){unset($o->$key);}"
                        + "$o=new stdClass;fieldSet($o,'selected',7);$before=[fieldRead($o,'selected'),fieldIsset($o,'selected')];"
                        + "fieldUnset($o,'selected');return [$before,fieldIsset($o,'selected'),property_exists($o,'selected')];",
                        PhpLanguageProfile.PHP_8_5, dumped);
                Memory result = module.include(env);
                assertEquals(7, result.valueOfIndex(0).valueOfIndex(0).toLong());
                assertTrue(result.valueOfIndex(0).valueOfIndex(1).toBoolean());
                assertFalse(result.valueOfIndex(1).toBoolean());
                assertFalse(result.valueOfIndex(2).toBoolean());
            } finally { env.doFinal(); }
        }
    }

    /** Historical test identity retained; the implemented native handler now carries the contract. */
    @Test public void testAllowDynamicPropertiesAttributeRemainsExplicitlyUnsupported() throws Throwable {
        for (String marker : new String[]{"AllowDynamicProperties", "\\AllowDynamicProperties"})
                for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope(); scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var output = new ByteArrayOutputStream(); Environment env = new Environment(scope, output);
            try {
                ModuleEntity module = compile(env, "#[" + marker + "] class DPAttribute {}"
                        + "class DPChild extends DPAttribute {} class DPPlain {} $events=[];"
                        + "set_error_handler(function($s,$m)use(&$events){$events[]=[$s,$m];return true;});"
                        + "$a=new DPAttribute;$b=new DPChild;$c=new stdClass;$p=new DPPlain;"
                        + "$a->x=1;unset($a->x);$a->x=2;$b->x=3;$c->x=4;$p->x=5;"
                        + "restore_error_handler();return [$events,$a->x,$b->x,$c->x,$p->x,"
                        + "(new ReflectionClass('DPAttribute'))->getAttributes()[0]->getName()];",
                        PhpLanguageProfile.PHP_8_5, dumped);
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                Memory result = module.include(env);
                ArrayMemory events = result.valueOfIndex(0).toValue(ArrayMemory.class);
                assertEquals("Only the unmarked class must warn", 1, events.size());
                assertEquals(8192, events.valueOfIndex(0).valueOfIndex(0).toLong());
                assertEquals("Creation of dynamic property DPPlain::$x is deprecated",
                        events.valueOfIndex(0).valueOfIndex(1).toString());
                for (int row = 1; row <= 4; row++) assertEquals(row + 1, result.valueOfIndex(row).toLong());
                assertEquals("AllowDynamicProperties", result.valueOfIndex(5).toString());
                env.flushAll(); assertEquals("All diagnostics captured", 0, output.size());
            } finally { env.doFinal(); }
        }
    }
}
