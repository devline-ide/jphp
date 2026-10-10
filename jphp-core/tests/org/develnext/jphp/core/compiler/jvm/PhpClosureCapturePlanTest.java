package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.*;
import php.runtime.loader.dump.io.*;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static org.objectweb.asm.Opcodes.*;

public class PhpClosureCapturePlanTest {
    private Environment environment(boolean debug, ByteArrayOutputStream output) {
        var scope = new CompileScope(); scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension()); scope.setDebugMode(debug);
        return new Environment(scope, output);
    }
    private byte[] resource(String name) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("resources/" + name)) {
            assertNotNull(name, input); return input.readAllBytes();
        }
    }
    private ModuleEntity compile(Environment env, String source, PhpLanguageProfile profile) throws Throwable {
        var context = new Context(source); context.setPhpLanguageProfile(profile); return new JvmCompiler(env, context).compile(false);
    }
    private byte[] save(Environment env, ModuleEntity module) throws IOException {
        var bytes = new ByteArrayOutputStream(); new ModuleDumper(module.getContext(), env, true).save(module, bytes); return bytes.toByteArray();
    }
    private void load(Environment env, ModuleEntity module) throws Throwable {
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        verify(module.getData(), env); for (var closure : module.getClosures()) verify(closure.getData(), env);
    }
    private void verify(byte[] bytes, Environment env) {
        var errors = new StringWriter(); CheckClassAdapter.verify(new ClassReader(bytes), env.getScope().getClassLoader(), false, new PrintWriter(errors));
        assertEquals(errors.toString(), "", errors.toString());
    }
    private void plan(ClosureEntity closure, ClosureEntity.CapturePlanOrigin origin, String[] names, ClosureEntity.CaptureMode... modes) {
        var plan = closure.getOwnershipCapturePlan(); assertNotNull(plan); assertEquals(origin, plan.origin());
        assertEquals(closure.getInternalName(), plan.generatedClassName()); assertTrue(plan.emptyShellConstructorVerified());
        assertEquals(64, plan.bytecodeSha256().length()); assertEquals(names.length, closure.uses.length);
        assertEquals(names.length, plan.entries().size());
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], plan.entries().get(i).name()); assertEquals(modes[i], plan.entries().get(i).mode());
            assertEquals(names[i], closure.uses[i].getName()); assertEquals(modes[i] == ClosureEntity.CaptureMode.CELL, closure.uses[i].isReference());
        }
        try { plan.entries().clear(); fail("Mutable capture plan"); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void orderedAstCapturePlanPreservesNativeValuesInAllBytecodeModes() throws Throwable {
        String source = new String(resource("closures/php85_capture_plan.php"), StandardCharsets.UTF_8).substring(5);
        String expected = new String(resource("closures/php85_capture_plan.expected"), StandardCharsets.UTF_8); int checks = 0;
        for (boolean strict : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var output = new ByteArrayOutputStream(); var env = environment(debug, output);
            try {
                var module = compile(env, (strict ? "declare(strict_types=1);" : "") + source, PhpLanguageProfile.PHP_8_5);
                if (dumped) module = new ModuleDumper(module.getContext(), env, true).load(new ByteArrayInputStream(save(env, module)));
                var closure = module.getClosures().iterator().next();
                plan(closure, dumped ? ClosureEntity.CapturePlanOrigin.VALIDATED_DUMP : ClosureEntity.CapturePlanOrigin.SOURCE_AST,
                        new String[]{"b", "a", "obj", "arr"}, ClosureEntity.CaptureMode.VALUE, ClosureEntity.CaptureMode.CELL,
                        ClosureEntity.CaptureMode.VALUE, ClosureEntity.CaptureMode.VALUE);
                assertFalse(closure.getOwnershipCapturePlan().staticClosure()); assertFalse(closure.getOwnershipCapturePlan().usesThis());
                load(env, module); module.include(env); env.flushAll(); assertEquals("debug="+debug+" dump="+dumped+" strict="+strict, expected, output.toString(StandardCharsets.UTF_8)); checks++;
            } finally { env.doFinal(); }
        }
        assertEquals(8, checks);
    }
    @Test public void emptyStaticArrowAndThisPlansComeFromActualAst() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var env = environment(debug, new ByteArrayOutputStream());
            try {
                var module = compile(env, "$a=2;$b=5;$empty=function(){return 1;};$static=static function()use($a){return$a;};"
                        + "$arrow=fn()=> $b+$a;class C{function f(){return function(){return$this;};}}return[$empty(),$static(),$arrow()];", PhpLanguageProfile.PHP_8_5);
                if (dumped) module = new ModuleDumper(module.getContext(), env, true).load(new ByteArrayInputStream(save(env, module)));
                var closures = new ArrayList<>(module.getClosures()); assertEquals(4, closures.size());
                var origin = dumped ? ClosureEntity.CapturePlanOrigin.VALIDATED_DUMP : ClosureEntity.CapturePlanOrigin.SOURCE_AST;
                plan(closures.get(0), origin, new String[0]); plan(closures.get(1), origin, new String[]{"a"}, ClosureEntity.CaptureMode.VALUE);
                plan(closures.get(2), origin, new String[]{"a", "b"}, ClosureEntity.CaptureMode.VALUE, ClosureEntity.CaptureMode.VALUE);
                plan(closures.get(3), origin, new String[0]); assertTrue(closures.get(1).getOwnershipCapturePlan().staticClosure());
                assertTrue(closures.get(3).getOwnershipCapturePlan().usesThis()); load(env, module);
                assertEquals(7, module.include(env).toValue(php.runtime.memory.ArrayMemory.class).valueOfIndex(2).toLong());
            } finally { env.doFinal(); }
        }
    }
    @Test public void historicalAbiEightAndLegacyRawMetadataNeverAcquireAuthority() throws Throwable {
        var env = environment(false, new ByteArrayOutputStream());
        try {
            var context = new Context("return 0;"); var dumper = new ModuleDumper(context, env, true);
            byte[] bytes = resource("compiler_provenance_abi8.phb"); assertEquals(8, ModuleDumper.readHeader(new ByteArrayInputStream(bytes)).getCompilerAbiVersion());
            var module = dumper.load(new ByteArrayInputStream(bytes)); assertNull(module.getClosures().iterator().next().getOwnershipCapturePlan());
            assertEquals(0, module.getClosures().iterator().next().uses.length);
            for (int i = 0; i < 2; i++) {
                bytes = save(env, module); assertEquals(9, ModuleDumper.readHeader(new ByteArrayInputStream(bytes)).getCompilerAbiVersion());
                module = dumper.load(new ByteArrayInputStream(bytes)); assertEquals(8, module.getCompilerProvenance().getCompilerAbiVersion());
                assertNull(module.getClosures().iterator().next().getOwnershipCapturePlan());
            }
            load(env, module); assertEquals(9, module.include(env).toLong());
            var legacy = compile(env, "$v=3;$c=function() use (&$v) { return $v; }; return $c;", PhpLanguageProfile.JPHP_LEGACY);
            assertNull(legacy.getClosures().iterator().next().getOwnershipCapturePlan()); assertTrue(legacy.getClosures().iterator().next().uses[0].isReference());
        } finally { env.doFinal(); }
    }
    private byte[] closureBytes(Environment env, ClosureEntity closure) throws IOException {
        var bytes = new ByteArrayOutputStream(); new ClosureDumper(closure.getContext(), env, true).save(closure, bytes); return bytes.toByteArray();
    }
    private byte[] metadata(ClosureEntity.CapturePlan plan, int version, String digest, int count, int mode, String... names) throws IOException {
        var bytes = new ByteArrayOutputStream(); var data = new DumpOutputStream(bytes);
        data.writeInt(ClosureDumper.CAPTURE_PLAN_STAMP); data.writeInt(version); data.writeName(plan.generatedClassName()); data.writeName(digest);
        data.writeBoolean(plan.staticClosure()); data.writeBoolean(plan.usesThis()); data.writeInt(count);
        for (String name : names) { data.writeName(name); data.writeByte(mode); } return bytes.toByteArray();
    }
    private byte[] tail(byte[] good, byte[] metadata) throws IOException {
        int stamp = -1; for (int i = 4; i < good.length - 3; i++)
            if (good[i] == 0x43 && good[i+1] == 0x50 && good[i+2] == 0x4c && good[i+3] == 0x4e) stamp = i;
        assertTrue(stamp > 4); var bytes = new ByteArrayOutputStream(); bytes.write(good, 0, stamp - 4);
        new DumpOutputStream(bytes).writeRawData(metadata); return bytes.toByteArray();
    }
    @Test public void malformedCaptureDescriptorsRejectBeforeBytecodeLoadingOrBodyEffects() throws Throwable {
        var output = new ByteArrayOutputStream(); var env = environment(false, output);
        try {
            var module = compile(env, "$a=1;return function()use($a){echo'effects';return$a;};", PhpLanguageProfile.PHP_8_5);
            var closure = module.getClosures().iterator().next(); var plan = closure.getOwnershipCapturePlan(); byte[] good = closureBytes(env, closure);
            var valid = metadata(plan, 1, plan.bytecodeSha256(), 1, 1, "a"); var trailing = Arrays.copyOf(valid, valid.length + 1);
            List<byte[]> invalid = new ArrayList<>(); invalid.add(metadata(plan, 2, plan.bytecodeSha256(), 1, 1, "a"));
            invalid.add(metadata(plan, 1, plan.bytecodeSha256(), 2, 1, "a")); invalid.add(metadata(plan, 1, plan.bytecodeSha256(), -1, 1));
            invalid.add(metadata(plan, 1, plan.bytecodeSha256(), 1, 0, "a")); invalid.add(metadata(plan, 1, plan.bytecodeSha256(), 1, 2, "a"));
            invalid.add(metadata(plan, 1, plan.bytecodeSha256(), 1, 1, "wrong")); invalid.add(metadata(plan, 1, "0".repeat(64), 1, 1, "a"));
            invalid.add(trailing); invalid.add(Arrays.copyOf(valid, valid.length - 1)); var signature = valid.clone(); signature[0] = 0; invalid.add(signature);
            int flags = 8 + 4 + 2 + plan.generatedClassName().length() + 4 + 2 + 64; var badFlags = valid.clone(); badFlags[flags] = 2; invalid.add(badFlags);
            for (byte[] bad : invalid) {
                try { new ClosureDumper(module.getContext(), env, true).load(new ByteArrayInputStream(tail(good, bad)), 9); fail("Malformed capture plan accepted"); }
                catch (IOException expected) { }
                assertEquals("", output.toString(StandardCharsets.UTF_8));
            }
            var restored = new ClosureDumper(module.getContext(), env, true).load(new ByteArrayInputStream(good), 9);
            assertTrue(restored.getOwnershipCapturePlan().emptyShellConstructorVerified());
            closure.uses[0].setReference(true);
            try { closure.getOwnershipCapturePlan(); fail("Stale mutable metadata accepted"); } catch (IllegalArgumentException expected) { }
        } finally { env.doFinal(); }
    }
    private byte[] changed(byte[] original, java.util.function.Consumer<ClassNode> mutate) {
        var node = new ClassNode(); new ClassReader(original).accept(node, 0); mutate.accept(node);
        var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    @Test public void actualShellVerifierRejectsChangedConstructorsFieldsParentsAndMalformedClasses() throws Throwable {
        var env = environment(false, new ByteArrayOutputStream());
        try {
            var module = compile(env, "return function(){return 1;};", PhpLanguageProfile.PHP_8_5);
            var closure = module.getClosures().iterator().next(); byte[] good = closure.getData(); String name = closure.getInternalName();
            assertTrue(PhpClosureShellVerifier.verify(good, name)); assertFalse(PhpClosureShellVerifier.verify(good, "WrongClass"));
            assertFalse(PhpClosureShellVerifier.verify(changed(good, n -> n.superName = "php/runtime/lang/BaseObject"), name));
            assertFalse(PhpClosureShellVerifier.verify(changed(good, n -> n.fields.add(new FieldNode(ACC_PUBLIC, "hiddenOwner", "Lphp/runtime/Memory;", null, null))), name));
            assertFalse(PhpClosureShellVerifier.verify(changed(good, n -> n.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow().instructions.insert(new InsnNode(NOP))), name));
            assertFalse(PhpClosureShellVerifier.verify(changed(good, n -> {
                var m = n.methods.stream().filter(x -> x.name.equals("<init>")).findFirst().orElseThrow();
                for (var i : m.instructions) if (i instanceof MethodInsnNode) ((MethodInsnNode)i).owner = "php/runtime/lang/BaseObject";
            }), name));
            assertFalse(PhpClosureShellVerifier.verify(changed(good, n -> {
                var extra = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null); extra.instructions.add(new InsnNode(RETURN)); n.methods.add(extra);
            }), name));
            for (byte[] bad : new byte[][]{Arrays.copyOf(good, 3), Arrays.copyOf(good, good.length - 1), Arrays.copyOf(good, good.length + 1)}) {
                try { PhpClosureShellVerifier.verify(bad, name); fail("Malformed class accepted"); } catch (IllegalArgumentException expected) { }
            }
        } finally { env.doFinal(); }
    }
    @Test public void metadataOnlyDumpsAndUnplannedAbiNineClosuresStayUncertified() throws Throwable {
        var env = environment(true, new ByteArrayOutputStream());
        try {
            var module = compile(env, "return function(){return 1;};", PhpLanguageProfile.PHP_8_5);
            var closure = module.getClosures().iterator().next(); var dumper = new ClosureDumper(module.getContext(), env, true);
            dumper.setIncludeData(false); var bytes = new ByteArrayOutputStream(); dumper.save(closure, bytes);
            var loaded = dumper.load(new ByteArrayInputStream(bytes.toByteArray()), 9); assertNull(loaded.getOwnershipCapturePlan());
            assertEquals(0, loaded.getData().length);
            var legacy = compile(env, "return function(){return 1;};", PhpLanguageProfile.JPHP_LEGACY);
            var raw = legacy.getClosures().iterator().next(); assertNull(raw.getOwnershipCapturePlan());
            loaded = new ClosureDumper(raw.getContext(), env, true).load(new ByteArrayInputStream(closureBytes(env, raw)), 9);
            assertNull(loaded.getOwnershipCapturePlan()); assertTrue(PhpClosureShellVerifier.verify(loaded.getData(), loaded.getInternalName()));
        } finally { env.doFinal(); }
    }
}
