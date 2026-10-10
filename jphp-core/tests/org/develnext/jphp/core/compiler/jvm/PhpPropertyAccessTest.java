package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.output.serialization.PhpSerialization;
import php.runtime.reflection.ModuleEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Native PHP 8.5.11 property visibility, catchable errors and magic ordering. */
public class PhpPropertyAccessTest extends JvmCompilerCase {
    private Environment environment(boolean debug) {
        var scope = newScope(); scope.setDebugMode(debug);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope, new ByteArrayOutputStream());
    }

    private ModuleEntity compile(Environment env, String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Context context = new Context(source); context.setPhpLanguageProfile(profile);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        return module;
    }

    private String resource(String suffix) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_access." + suffix)) {
            assertNotNull(input); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void verifyNative(boolean debug) throws Throwable {
        ArrayList<String> failures = new ArrayList<>();
        for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            Environment env = environment(debug);
            try {
                var module = compile(env, (strict ? "declare(strict_types=1);" : "") + resource("php").substring(5), PhpLanguageProfile.PHP_8_5, dumped);
                TraceInfo trace = TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
                ArrayMemory actual = module.include(env).toValue(ArrayMemory.class);
                ArrayMemory expected = PhpSerialization.unserialize(env, trace, StringMemory.valueOf(resource("expected"))).toValue(ArrayMemory.class);
                assertEquals(132, actual.size()); assertEquals(132, expected.size());
                for (int row = 0; row < 132; row++) {
                    String wanted = PhpSerialization.serialize(env, trace, expected.valueOfIndex(row)).toString();
                    String got = PhpSerialization.serialize(env, trace, actual.valueOfIndex(row)).toString();
                    if (!wanted.equals(got)) failures.add("debug=" + debug + " strict=" + strict + " dump=" + dumped + " row=" + row + " expected=" + wanted + " actual=" + got);
                }
            } finally { env.doFinal(); }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test public void testNativeCatchablePropertyAccessAndMagicOrdering() throws Throwable { verifyNative(true); }
    @Test public void testNativeOptimizedCatchablePropertyAccessAndMagicOrdering() throws Throwable { verifyNative(false); }

    @Test public void testVisibilityErrorClassAndCallerProfileBoundary() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) for (PhpLanguageProfile classProfile : PhpLanguageProfile.values())
            for (PhpLanguageProfile callerProfile : PhpLanguageProfile.values()) {
                Environment env = environment(true);
                try {
                    compile(env, "class PAXMixed {private $p=1;private static $s=3;function snapshot(){return [$this->p,self::$s];}} $GLOBALS['pax_mixed']=new PAXMixed;",
                            classProfile, dumped).include(env);
                    boolean modern = classProfile == PhpLanguageProfile.PHP_8_5 || callerProfile == PhpLanguageProfile.PHP_8_5;
                    for (String expression : new String[]{"$GLOBALS['pax_mixed']->p", "PAXMixed::$s", "($GLOBALS['pax_mixed']->p=9)", "++$GLOBALS['pax_mixed']->p"}) {
                        var module = compile(env, "try{$v=" + expression + ";return ['accepted',$v];}catch(Error $e){return [get_class($e),$e->getMessage()];}", callerProfile, dumped);
                        if (modern) {
                            ArrayMemory result = module.include(env).toValue(ArrayMemory.class);
                            assertEquals("Error", result.valueOfIndex(0).toString());
                            assertEquals("Cannot access private property PAXMixed::$" + (expression.contains("::$s") ? "s" : "p"), result.valueOfIndex(1).toString());
                        } else {
                            try { module.include(env); fail("Legacy access must retain its fatal ErrorException"); }
                            catch (ErrorException error) { assertTrue(error.getMessage(), error.getMessage().contains("Cannot access private property PAXMixed::$")); }
                        }
                    }
                    var unchanged = compile(env, "return $GLOBALS['pax_mixed']->snapshot();", callerProfile, dumped).include(env).toValue(ArrayMemory.class);
                    assertEquals(1, unchanged.valueOfIndex(0).toLong()); assertEquals(3, unchanged.valueOfIndex(1).toLong());
                } finally { env.doFinal(); }
            }
    }

    @Test public void testModernMagicReferenceFromLegacyCallerDoesNotAcquirePrivateGuard() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            Environment env = environment(true);
            try {
                compile(env, "class PAXMagic {private int $p=1;public $back='abc';public $calls=0;function &__get($k){$this->calls++;return $this->back;}function own(){return $this->p;}}$GLOBALS['pax_magic']=new PAXMagic;",
                        PhpLanguageProfile.PHP_8_5, dumped).include(env);
                var actual = compile(env, "$o=$GLOBALS['pax_magic'];$r=&$o->p;$r='changed';return [$o->back,$o->own(),$o->calls];",
                        PhpLanguageProfile.JPHP_LEGACY, dumped).include(env).toValue(ArrayMemory.class);
                assertEquals("changed", actual.valueOfIndex(0).toString()); assertEquals(1, actual.valueOfIndex(1).toLong()); assertEquals(1, actual.valueOfIndex(2).toLong());
            } finally { env.doFinal(); }
        }
    }
}
