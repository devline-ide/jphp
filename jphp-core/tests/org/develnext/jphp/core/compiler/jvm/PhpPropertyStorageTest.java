package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.cache.PropertyCallCache;
import php.runtime.lang.BaseObject;
import php.runtime.lang.ForeachIterator;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.output.PrintR;
import php.runtime.memory.output.VarDump;
import php.runtime.memory.output.VarExport;
import php.runtime.memory.output.serialization.PhpSerialization;
import php.runtime.memory.support.ArrayMemoryMap;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Physical buckets, declaration anchors and their native PHP 8.5.11 observable contracts. */
public class PhpPropertyStorageTest extends JvmCompilerCase {
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
    private Environment environment(boolean debug) {
        var scope = newScope(); scope.setDebugMode(debug);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope, new ByteArrayOutputStream());
    }
    private String resource(String suffix) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_storage." + suffix)) {
            assertNotNull(input); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void testPhysicalBucketHashOrderResizeAndIdentityRemoval() {
        ArrayMemoryMap map = new ArrayMemoryMap();
        var first = map.insertBucket("x", LongMemory.valueOf(4));
        var latest = map.insertBucket("x", LongMemory.valueOf(1));
        for (int i = 0; i < 80; i++) map.put("field" + i, LongMemory.valueOf(i));
        assertSame(latest, map.getEntry("x")); assertEquals(82, map.size());
        var entries = map.entrySet().iterator(); assertSame(first, entries.next()); entries.remove();
        assertNull(map.bucketAt(0)); assertSame(latest, map.getEntry("x")); assertSame(latest, entries.next());
        ArrayMemoryMap copy = new ArrayMemoryMap(map);
        assertNull(copy.bucketAt(0)); assertEquals(1, copy.getEntry("x").toLong()); assertEquals(map.bucketLimit(), copy.bucketLimit());
        map.remove("x"); assertNull(map.bucketAt(1)); assertNull(map.getEntry("x"));
        assertEquals(1, copy.getEntry("x").toLong());
        assertFalse(new php.runtime.memory.PhpObjectProperties().hasBucketLayout());
        ArrayMemoryMap ordinary = new ArrayMemoryMap(); ordinary.put("x", Memory.CONST_INT_1); ordinary.put("x", LongMemory.valueOf(2));
        assertEquals(1, ordinary.size()); assertFalse(ordinary.hasBucketLayout());
    }

    @Test public void testCopyOnWriteOrdinalCarrierAndDetachedBucket() {
        ArrayMemory array = new ArrayMemory(); array.insertBucket("x", LongMemory.valueOf(4)); array.insertBucket("x", Memory.CONST_INT_1);
        ArrayMemory snapshot = (ArrayMemory) array.toImmutable();
        ForeachIterator entries = array.foreachIterator(true, false); assertTrue(entries.next());
        ReferenceMemory older = (ReferenceMemory) entries.getValue();
        older.assign(8); older.assign(9);
        assertEquals(9, array.bucketAt(0).toLong()); assertEquals(4, snapshot.bucketAt(0).toLong());
        assertEquals(1, array.getByScalar("x").toLong()); assertEquals(2, array.size());
        assertNotSame(array.getStorageIdentity(), snapshot.getStorageIdentity());
        array.removeByScalar("x"); array.removeByScalar("x");
        ArrayMemory emptySnapshot = (ArrayMemory) array.toImmutable(); older.assign(12);
        assertEquals(0, array.size()); assertEquals(0, emptySnapshot.size()); assertEquals(12, older.toLong());
        ArrayMemory copied = snapshot.duplicate(); assertEquals(2, copied.size()); assertEquals(4, copied.bucketAt(0).toLong());
        assertEquals(1, copied.bucketAt(1).toLong());
    }

    private void verifyNative(boolean debug) throws Throwable {
        ArrayList<String> failures = new ArrayList<>();
        for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            Environment env = environment(debug);
            try {
                ModuleEntity module = compile(env, (strict ? "declare(strict_types=1);" : "") + resource("php").substring(5), PhpLanguageProfile.PHP_8_5, dumped);
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                ArrayMemory actual = module.include(env).toValue(ArrayMemory.class);
                TraceInfo trace = TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
                ArrayMemory expected = PhpSerialization.unserialize(env, trace, StringMemory.valueOf(resource("expected"))).toValue(ArrayMemory.class);
                assertEquals(24, actual.size()); assertEquals(24, expected.size());
                for (int row = 0; row < 24; row++) {
                    String wanted = PhpSerialization.serialize(env, trace, expected.valueOfIndex(row)).toString();
                    String got = PhpSerialization.serialize(env, trace, actual.valueOfIndex(row)).toString();
                    if (!wanted.equals(got)) failures.add("debug=" + debug + " strict=" + strict + " dump=" + dumped + " row=" + row + " expected=" + wanted + " actual=" + got);
                }
                env.getDefaultBuffer().flush();
            } finally { env.doFinal(); }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }
    @Test public void testNativeAnchorsReferencesMagicAndPhysicalViews() throws Throwable { verifyNative(true); }
    @Test public void testNativeOptimizedAnchorsReferencesMagicAndPhysicalViews() throws Throwable { verifyNative(false); }

    @Test public void testDynamicOffsetsValidateCurrentObjectAndLiveBucket() throws Throwable {
        Environment env = environment(true);
        try {
            compile(env, "class PSTCacheBox {}", PhpLanguageProfile.PHP_8_5, false).include(env);
            var clazz = env.fetchClass("PSTCacheBox", false); PropertyCallCache cache = new PropertyCallCache();
            var one = new BaseObject(env, clazz) {}; var two = new BaseObject(env, clazz) {};
            one.getProperties().insertBucket("x", LongMemory.valueOf(4));
            assertEquals(4, clazz.getProperty(env, TraceInfo.UNKNOWN, one, "x", cache, 0).toLong());
            one.getProperties().insertBucket("x", Memory.CONST_INT_1);
            assertEquals(4, clazz.getProperty(env, TraceInfo.UNKNOWN, one, "x", cache, 0).toLong());
            assertEquals(1, clazz.getProperty(env, TraceInfo.UNKNOWN, one, "x", null, 0).toLong());
            two.getProperties().insertBucket("y", LongMemory.valueOf(8)); two.getProperties().insertBucket("x", LongMemory.valueOf(9));
            assertEquals(9, clazz.getProperty(env, TraceInfo.UNKNOWN, two, "x", cache, 0).toLong());
            two.getProperties().removeByScalar("x"); two.getProperties().insertBucket("x", LongMemory.valueOf(10));
            assertEquals(10, clazz.getProperty(env, TraceInfo.UNKNOWN, two, "x", cache, 0).toLong());
            assertEquals(4, one.getProperties().bucketAt(0).toLong());
            assertNull(cache.get(env, 0));
        } finally { env.doFinal(); }
    }

    @Test public void testDebugConsumersShowAnchorsAndDuplicateBuckets() throws Throwable {
        Environment env = environment(true);
        try {
            ObjectMemory object = compile(env, "class PSTDebug {public $x=1;public int $t;public $y=2;}$o=new PSTDebug;unset($o->x);return $o;", PhpLanguageProfile.PHP_8_5, false).include(env).toValue(ObjectMemory.class);
            StringWriter dump = new StringWriter(); new VarDump(env, dump).print(object);
            assertTrue(dump.toString(), dump.toString().contains(" (1) {")); assertTrue(dump.toString().contains("uninitialized(int)"));
            assertFalse(dump.toString().contains("[\"x\"]"));
            StringWriter printed = new StringWriter(); new PrintR(env, printed).print(object);
            assertFalse(printed.toString().contains("[x]")); assertFalse(printed.toString().contains("[t]")); assertTrue(printed.toString().contains("[y]"));
            object.getProperties().insertBucket("d", LongMemory.valueOf(4)); object.getProperties().insertBucket("d", Memory.CONST_INT_1);
            printed = new StringWriter(); new PrintR(env, printed).print(object);
            assertTrue(printed.toString(), printed.toString().contains("[d] => 4")); assertTrue(printed.toString(), printed.toString().contains("[d] => 1"));
            StringWriter exported = new StringWriter(); new VarExport(env, exported).print(object);
            assertTrue(exported.toString(), exported.toString().contains("'d' => 4")); assertTrue(exported.toString(), exported.toString().contains("'d' => 1"));
        } finally { env.doFinal(); }
    }

    @Test public void testNativePrivateDeclarationScopeAndStickyCursorInvalidity() throws Throwable {
        String source, expected;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_storage_private_cursor.php")) {
            assertNotNull(input); source = new String(input.readAllBytes(), StandardCharsets.UTF_8).substring(5);
        }
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_storage_private_cursor.expected")) {
            assertNotNull(input); expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (boolean debug : new boolean[]{false, true}) for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            Environment env = environment(debug);
            try {
                ModuleEntity module = compile(env, (strict ? "declare(strict_types=1);" : "") + source, PhpLanguageProfile.PHP_8_5, dumped);
                Memory actual = module.include(env);
                TraceInfo trace = TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
                assertEquals("debug=" + debug + " strict=" + strict + " dump=" + dumped, expected, PhpSerialization.serialize(env, trace, actual).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testNativeInheritedPrivateDynamicAndMagicResolution() throws Throwable {
        String source, expected;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_private_resolution.php")) {
            assertNotNull(input); source = new String(input.readAllBytes(), StandardCharsets.UTF_8).substring(5);
        }
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_private_resolution.expected")) {
            assertNotNull(input); expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (boolean debug : new boolean[]{false, true}) for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            Environment env = environment(debug);
            try {
                ModuleEntity module = compile(env, (strict ? "declare(strict_types=1);" : "") + source, PhpLanguageProfile.PHP_8_5, dumped);
                Memory actual = module.include(env);
                TraceInfo trace = TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
                assertEquals("debug=" + debug + " strict=" + strict + " dump=" + dumped, expected, PhpSerialization.serialize(env, trace, actual).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testInheritedPrivateCacheUsesCallerProfileAndOwnPrivateStillDenies() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            Environment env = environment(true);
            try {
                ObjectMemory object = compile(env, "class PSROldParent {private $hidden=1;} class PSROldChild extends PSROldParent {} return new PSROldChild;",
                        PhpLanguageProfile.JPHP_LEGACY, dumped).include(env).toValue(ObjectMemory.class);
                object.getProperties().putAsKeyString("hidden", LongMemory.valueOf(9));
                var clazz = object.getReflection(); PropertyCallCache cache = new PropertyCallCache();
                try { clazz.getProperty(env, TraceInfo.UNKNOWN, object.value, "hidden", cache, 0); fail("Legacy private access must deny"); }
                catch (php.runtime.exceptions.support.ErrorException error) { assertTrue(error.getMessage(), error.getMessage().contains("Cannot access private property PSROldParent::$hidden")); }
                ModuleEntity modern = compile(env, "return null;", PhpLanguageProfile.PHP_8_5, dumped);
                TraceInfo trace = TraceInfo.valueOf(modern.getContext().getFileName(), 0, 0, modern.getInternalName());
                assertEquals(9, clazz.getProperty(env, trace, object.value, "hidden", cache, 0).toLong());
                clazz.getRefProperty(env, trace, object.value, "hidden", cache, 0).assign(10);
                assertEquals(10, clazz.getProperty(env, trace, object.value, "hidden", cache, 0).toLong());
                ObjectMemory own = compile(env, "class PSROwnPrivate {private $hidden=1;} return new PSROwnPrivate;",
                        PhpLanguageProfile.PHP_8_5, dumped).include(env).toValue(ObjectMemory.class);
                try { own.getReflection().getProperty(env, trace, own.value, "hidden", cache, 0); fail("Own private access must deny"); }
                catch (php.runtime.lang.exception.BaseError error) { assertEquals("Cannot access private property PSROwnPrivate::$hidden", error.getMessage(env).toString()); }
            } finally { env.doFinal(); }
        }
    }

    private void verifyRoundTripRow(int row) throws Throwable {
        String source, wire;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_storage_roundtrip.php")) {
            assertNotNull(input); source = new String(input.readAllBytes(), StandardCharsets.UTF_8).substring(5);
        }
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_storage_roundtrip.expected")) {
            assertNotNull(input); wire = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (boolean debug : new boolean[]{false, true}) for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            Environment env = environment(debug);
            try {
                var module = compile(env, (strict ? "declare(strict_types=1);" : "") + source, PhpLanguageProfile.PHP_8_5, dumped);
                var actual = module.include(env).toValue(ArrayMemory.class);
                TraceInfo trace = TraceInfo.valueOf(module.getContext().getFileName(), 0, 0, module.getInternalName());
                var expected = PhpSerialization.unserialize(env, trace, StringMemory.valueOf(wire)).toValue(ArrayMemory.class);
                assertEquals("row=" + row + " debug=" + debug + " strict=" + strict + " dump=" + dumped,
                        PhpSerialization.serialize(env, trace, expected.valueOfIndex(row)).toString(),
                        PhpSerialization.serialize(env, trace, actual.valueOfIndex(row)).toString());
            } finally { env.doFinal(); }
        }
    }
    @Test public void testNativeCastRoundTripAndNumericSlowPath() throws Throwable { verifyRoundTripRow(0); }
    @Test public void testNativePhysicalBucketPointerMovement() throws Throwable { verifyRoundTripRow(1); }

    @Test public void testLegacyUnsetOrderAndLegacyWriterModernAnchorBridge() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            Environment env = environment(true);
            try {
                assertEquals("O:11:\"PSTOldOrder\":2:{s:1:\"y\";i:2;s:1:\"x\";i:3;}", compile(env,
                        "class PSTOldOrder {public $x=1;public $y=2;}$o=new PSTOldOrder;unset($o->x);$o->x=3;return serialize($o);",
                        PhpLanguageProfile.JPHP_LEGACY, dumped).include(env).toString());
                compile(env, "class PSTModernAnchor {public $x=1;public int $t;public $y=2;} $GLOBALS['pst_bridge']=new PSTModernAnchor;unset($GLOBALS['pst_bridge']->x);",
                        PhpLanguageProfile.PHP_8_5, dumped).include(env);
                assertEquals("O:15:\"PSTModernAnchor\":1:{s:1:\"y\";i:2;}", compile(env, "return serialize($GLOBALS['pst_bridge']);",
                        PhpLanguageProfile.JPHP_LEGACY, dumped).include(env).toString());
            } finally { env.doFinal(); }
        }
    }
}
