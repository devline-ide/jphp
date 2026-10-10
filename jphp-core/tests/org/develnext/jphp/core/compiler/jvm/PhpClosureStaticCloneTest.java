package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.Closure;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Static slot VALUE copies, canonical CELL sharing and temporary call receiver binding. */
public class PhpClosureStaticCloneTest extends JvmCompilerCase {
    private String resource(String suffix) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_closure_static_clones." + suffix)) {
            assertNotNull(input);return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private ModuleEntity compile(Environment env, String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        var context = new Context(source.startsWith("<?php") ? source.substring(5) : source);context.setPhpLanguageProfile(profile);
        var module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            var dumper = new ModuleDumper(context, env, true);var bytes = new ByteArrayOutputStream();dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void nativeScalarArrayObjectReferenceAndCallControlsSourceAndDump() throws Throwable {
        String source = resource("php"), expected = resource("expected");List<String> differences = new ArrayList<>();
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var output = new ByteArrayOutputStream();var env = new Environment(scope, output);
            try {
                compile(env, source, PhpLanguageProfile.PHP_8_5, dumped).include(env);env.getDefaultBuffer().flush();
                String actual = output.toString(StandardCharsets.UTF_8);
                if (!expected.equals(actual)) differences.add("debug=" + debug + " dumped=" + dumped + " actual=" + actual);
            } catch (Throwable error) {differences.add("debug=" + debug + " dumped=" + dumped + " error=" + error);}
            finally {env.doFinal();}
        }
        assertTrue(String.join("\n", differences), differences.isEmpty());
    }
    @Test public void cloneStaticArrayUsesDistinctSlotsAndCopyOnWriteStorageSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                Closure source = compile(env, "$f=function(){static $x=[];$x[]=1;return $x;};$f();return $f;", PhpLanguageProfile.PHP_8_5, dumped).include(env).toObject(Closure.class);
                Closure copy = source.__clone(env, TraceInfo.UNKNOWN);assertNotSame(source, copy);
                var originalSlot = (ReferenceMemory)source.getStatic("x");var copiedSlot = (ReferenceMemory)copy.getStatic("x");assertNotSame(originalSlot, copiedSlot);
                var originalValue = (ArrayMemory)originalSlot.getRawValue();var copiedValue = (ArrayMemory)copiedSlot.getRawValue();assertNotSame(originalValue, copiedValue);
                assertSame(originalValue.getStorageIdentity(), copiedValue.getStorageIdentity());
                assertEquals(2, source.__invoke(env).toValue(ArrayMemory.class).size());assertEquals(1, copiedValue.size());
                assertNotSame(((ArrayMemory)originalSlot.getRawValue()).getStorageIdentity(), copiedValue.getStorageIdentity());
                assertEquals(2, copy.__invoke(env).toValue(ArrayMemory.class).size());
            } finally {env.doFinal();}
        }
    }
    @Test public void cloneExportedStaticCellIsSharedButItsContainerSlotsAreDistinctSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                Closure source = compile(env, "$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;return $f;", PhpLanguageProfile.PHP_8_5, dumped).include(env).toObject(Closure.class);
                Closure copy = source.__clone(env, TraceInfo.UNKNOWN);var originalSlot = (ReferenceMemory)source.getStatic("x");var copiedSlot = (ReferenceMemory)copy.getStatic("x");
                assertNotSame(originalSlot, copiedSlot);
                ReferenceMemory originalCell = originalSlot.isCanonicalReferenceCell() ? originalSlot : (ReferenceMemory)originalSlot.getRawValue();
                assertTrue(originalCell.isCanonicalReferenceCell());assertSame(originalCell, copiedSlot.getRawValue());
                copiedSlot.assign(9);assertEquals(9, originalSlot.toLong());assertEquals(9, copiedSlot.toLong());
            } finally {env.doFinal();}
        }
    }
    /** Unsuppressed: canonical-cell lifetime requires actual owner release, beyond the snapshot fix. */
    @Test public void nativeReleasedStaticReferenceDoesNotKeepCloneAliasedSourceAndDump() throws Throwable {
        String source = "$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo 'released-ref-static:',$t,',',$s,\"\\n\";";
        String expected = "released-ref-static:5,9\n";List<String> differences = new ArrayList<>();
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);var output = new ByteArrayOutputStream();var env = new Environment(scope, output);
            try {
                compile(env, source, PhpLanguageProfile.PHP_8_5, dumped).include(env);env.getDefaultBuffer().flush();
                String actual = output.toString(StandardCharsets.UTF_8);
                if (!expected.equals(actual)) differences.add("debug=" + debug + " dumped=" + dumped + " expected=" + expected + " actual=" + actual);
            } catch (Throwable error) {differences.add("debug=" + debug + " dumped=" + dumped + " error=" + error);}
            finally {env.doFinal();}
        }
        assertTrue(String.join("\n", differences), differences.isEmpty());
    }
    @Test public void legacyBindingStaticsAndCloneFactoryRejectionRemainSourceAndDump() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);var output = new ByteArrayOutputStream();var env = new Environment(scope, output);
            try {
                Closure source = compile(env, "$f=function(){static $x=0;return ++$x;};echo $f();$g=$f->bindTo(null);echo ',', $f(), ',', $g(), ',', $f(), ',', $g();return $f;", PhpLanguageProfile.JPHP_LEGACY, dumped).include(env).toObject(Closure.class);
                env.getDefaultBuffer().flush();assertEquals("1,2,3,4,5", output.toString(StandardCharsets.UTF_8));
                try {source.__clone(env, TraceInfo.UNKNOWN);fail("Legacy clone factory unexpectedly changed");}
                catch (IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("wrong number of arguments"));}
            } finally {env.doFinal();}
        }
    }
}
