package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.FunctionDumper;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.FunctionEntity;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class CompilerProvenanceTest extends JvmCompilerCase {
    private Environment environment(boolean debug) {
        Environment env = new Environment(newScope(), new ByteArrayOutputStream());
        env.getScope().setDebugMode(debug);
        return env;
    }
    private byte[] save(ModuleDumper dumper, ModuleEntity module) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        dumper.save(module, output);
        return output.toByteArray();
    }
    private byte[] resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/" + name)) {
            assertNotNull(name, input);
            return input.readAllBytes();
        }
    }
    private void provenance(ModuleEntity module, ModuleEntity.CompilerOrigin origin, int compilerAbi, int acceptedAbi) {
        var provenance = module.getCompilerProvenance();
        assertEquals(origin, provenance.getOrigin());
        assertEquals(compilerAbi >= 0, provenance.hasKnownCompilerAbi());
        assertEquals(compilerAbi, provenance.getCompilerAbiVersion());
        assertEquals(acceptedAbi, provenance.getAcceptedDumpAbiVersion());
    }
    private long execute(Environment env, ModuleEntity module) throws Throwable {
        env.getScope().loadModule(module);
        env.getScope().addUserModule(module);
        env.registerModule(module);
        StringWriter diagnostics = new StringWriter();
        PrintWriter writer = new PrintWriter(diagnostics);
        CheckClassAdapter.verify(new ClassReader(module.getData()), env.getScope().getClassLoader(), false, writer);
        for (var clazz : module.getClasses())
            if (clazz.getData() != null && clazz.getData().length != 0)
                CheckClassAdapter.verify(new ClassReader(clazz.getData()), env.getScope().getClassLoader(), false, writer);
        for (var function : module.getFunctions())
            CheckClassAdapter.verify(new ClassReader(function.getData()), env.getScope().getClassLoader(), false, writer);
        assertEquals("ASM", "", diagnostics.toString());
        return module.include(env).toLong();
    }

    @Test public void sourceCompilationSealsAbiOnlyAfterSuccessAndDumpOriginIsDistinct() throws Throwable {
        String source = new String(resource("compiler_provenance_abi7.php"), StandardCharsets.UTF_8).substring(5)
                .replace("public int $x", "public $x"); // Use shared syntax when testing both language profiles.
        for (boolean debug : new boolean[] {false, true}) {
            for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
                Environment env = environment(debug);
                try {
                    Context context = new Context(source);
                    context.setPhpLanguageProfile(profile);
                    JvmCompiler compiler = new JvmCompiler(env, context);
                    provenance(compiler.getModule(), ModuleEntity.CompilerOrigin.UNKNOWN, -1, -1);
                    ModuleEntity module = compiler.compile(false);
                    provenance(module, ModuleEntity.CompilerOrigin.SOURCE, ModuleDumper.COMPILER_ABI_VERSION, -1);
                    var immutable = module.getCompilerProvenance();
                    module.setPhpLanguageProfile(profile == PhpLanguageProfile.PHP_8_5
                            ? PhpLanguageProfile.JPHP_LEGACY : PhpLanguageProfile.PHP_8_5);
                    module.setCompiledArtifact(true);
                    assertSame(immutable, module.getCompilerProvenance());
                    module.setPhpLanguageProfile(profile);
                    try { module.recordSourceCompilerAbi(6); fail("Compiler ABI changed after publication"); }
                    catch (IllegalStateException expected) { }
                    assertSame(immutable, module.getCompilerProvenance());
                    ModuleDumper dumper = new ModuleDumper(context, env, true);
                    byte[] bytes = save(dumper, module);
                    assertEquals(ModuleDumper.COMPILER_ABI_VERSION, ModuleDumper.readHeader(new ByteArrayInputStream(bytes)).getCompilerAbiVersion());
                    assertEquals(0, new DataInputStream(new ByteArrayInputStream(bytes, reservedTailOffset(bytes), 4)).readInt());
                    ModuleEntity loaded = dumper.load(new ByteArrayInputStream(bytes));
                    provenance(loaded, ModuleEntity.CompilerOrigin.DUMP, ModuleDumper.COMPILER_ABI_VERSION, ModuleDumper.COMPILER_ABI_VERSION);
                    assertTrue(loaded.isCompiledArtifact());
                    assertEquals(profile, loaded.getPhpLanguageProfile());
                    try { loaded.recordDumpCompilerAbi(1, 7); fail("Loaded provenance changed"); }
                    catch (IllegalStateException expected) { }
                    assertEquals(8, execute(env, loaded));
                } finally { env.doFinal(); }
            }
        }
    }

    @Test public void realGoldenAbiZeroThroughNineCannotBeLaunderedByRepeatedRedumps() throws Throwable {
        long[] expected = {42, 3, 3, 5, 7, 7, 7, 8, 9, 9};
        for (int abi = 0; abi <= 9; abi++) {
            Environment env = environment(true);
            try {
                String name = abi == 0 ? "legacy_jphp.phb" : abi >= 7 ? "compiler_provenance_abi" + abi + ".phb" : "abi" + abi + "_jphp.phb";
                byte[] bytes = resource(name);
                Context context = new Context("return 0;");
                ModuleDumper dumper = new ModuleDumper(context, env, true);
                var header = ModuleDumper.readHeader(new ByteArrayInputStream(bytes));
                assertEquals(name, abi, header.getCompilerAbiVersion());
                ModuleEntity module = dumper.load(new ByteArrayInputStream(bytes));
                provenance(module, ModuleEntity.CompilerOrigin.DUMP, abi, abi);
                for (int round = 0; round < 2; round++) {
                    bytes = save(dumper, module);
                    header = ModuleDumper.readHeader(new ByteArrayInputStream(bytes));
                    assertEquals("Transport retains current ABI", ModuleDumper.COMPILER_ABI_VERSION, header.getCompilerAbiVersion());
                    module = dumper.load(new ByteArrayInputStream(bytes));
                    provenance(module, ModuleEntity.CompilerOrigin.DUMP, abi, ModuleDumper.COMPILER_ABI_VERSION);
                }
                assertEquals(name, expected[abi], execute(env, module));
            } finally { env.doFinal(); }
        }
    }

    public static Memory nativeBody(Environment env, Memory[] args, ArrayMemory locals) { return Memory.CONST_INT_1; }
    @Test public void manualBytecodeAndNativeModulesRemainUncertifiedAcrossDumping() throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            Environment env = environment(false);
            try {
                Context context = new Context("return 23;");
                context.setPhpLanguageProfile(profile);
                ModuleEntity source = new JvmCompiler(env, context).compile(false);
                ModuleEntity manual = new ModuleEntity(context);
                manual.setPhpLanguageProfile(profile);
                manual.setCompiledArtifact(true);
                manual.setInternalName(source.getInternalName());
                manual.setData(source.getData());
                provenance(manual, ModuleEntity.CompilerOrigin.UNKNOWN, -1, -1);
                ModuleDumper dumper = new ModuleDumper(context, env, true);
                for (int round = 0; round < 2; round++) {
                    manual = dumper.load(new ByteArrayInputStream(save(dumper, manual)));
                    provenance(manual, ModuleEntity.CompilerOrigin.DUMP, -1, ModuleDumper.COMPILER_ABI_VERSION);
                }
                assertEquals(23, execute(env, manual));
                ModuleEntity nativeModule = new ModuleEntity(context);
                nativeModule.setPhpLanguageProfile(profile);
                nativeModule.setNativeMethod(getClass().getMethod("nativeBody", Environment.class, Memory[].class, ArrayMemory.class));
                assertEquals(1, nativeModule.include(env).toLong());
                provenance(nativeModule, ModuleEntity.CompilerOrigin.UNKNOWN, -1, -1);
            } finally { env.doFinal(); }
        }
    }

    private static class ObservingDumper extends ModuleDumper {
        FunctionEntity observed;
        ObservingDumper(Context context, Environment env) {
            super(context, env, true);
            functionDumper = new FunctionDumper(context, env, true) {
                @Override public FunctionEntity load(InputStream input) throws IOException {
                    return observed = super.load(input);
                }
            };
        }
    }
    private byte[] metadata(int... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        for (int value : values) output.writeInt(value);
        return bytes.toByteArray();
    }
    private static int generatorFrameOffset(byte[] dump) {
        int result=-1;
        for(int at=4;at+12<=dump.length;at++) {
            var frame=java.nio.ByteBuffer.wrap(dump,at,12);
            if(frame.getInt()!=0x474c4e4b || frame.getInt()!=1)continue;
            int length=frame.getInt();if(length<0 || length>16*1024*1024 || length>dump.length-at-12)continue;
            int end=at+12+length;
            boolean suffix=end==dump.length;
            if(!suffix && end+12<=dump.length) {
                int attributes=java.nio.ByteBuffer.wrap(dump,end,4).getInt();
                suffix=attributes>=8 && attributes==dump.length-end-4
                        && java.nio.ByteBuffer.wrap(dump,end+4,4).getInt()==0x41544d44;
            }
            if(suffix){assertEquals("unique actual GLNK frame",-1,result);result=at;}
        }
        assertTrue("actual bounded GLNK frame",result>=4);return result;
    }
    private int reservedTailOffset(byte[] dump) { return generatorFrameOffset(dump)-4; }
    private byte[] replaceEmptyTail(byte[] dump, byte[] metadata) throws IOException {
        int tail=reservedTailOffset(dump);
        assertEquals(0, new DataInputStream(new ByteArrayInputStream(dump, tail, 4)).readInt());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(dump, 0, tail);
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(metadata.length);
        output.write(metadata);
        // Perturbed provenance cannot keep the original generator linkage authority.
        int frame=generatorFrameOffset(dump),length=java.nio.ByteBuffer.wrap(dump,frame+8,4).getInt();
        output.writeInt(0x474c4e4b);output.writeInt(1);output.writeInt(0);
        bytes.write(dump,frame+12+length,dump.length-frame-12-length);
        return bytes.toByteArray();
    }

    @Test public void malformedKnownMetadataNeverPublishesPartialProvenance() throws Throwable {
        Environment env = environment(true);
        try {
            Context context = new Context("function provenance_probe(){return 5;}return provenance_probe();");
            byte[] good = save(new ModuleDumper(context, env, true), new JvmCompiler(env, context).compile(false));
            int stamp = ModuleDumper.COMPILER_PROVENANCE_STAMP;
            byte[][] invalid = {
                    metadata(stamp), metadata(stamp, 1), metadata(stamp, 1, 1),
                    metadata(stamp, 2, 1, 7), metadata(stamp, 1, 0, 7), metadata(stamp, 1, 2, 7),
                    metadata(stamp, 1, -1, 7), metadata(stamp, 1, 1, -2), metadata(stamp, 1, 1, ModuleDumper.COMPILER_ABI_VERSION + 1),
                    metadata(stamp, 1, 1, 7, 99)
            };
            ObservingDumper dumper = new ObservingDumper(context, env);
            for (byte[] payload : invalid) {
                try { dumper.load(new ByteArrayInputStream(replaceEmptyTail(good, payload))); fail("Malformed metadata accepted"); }
                catch (IOException expected) { }
                assertNotNull("Real function body was parsed before the invalid final metadata", dumper.observed);
                ModuleEntity partial = dumper.observed.getModule();
                provenance(partial, ModuleEntity.CompilerOrigin.UNKNOWN, -1, -1);
                ModuleEntity recovered = dumper.load(new ByteArrayInputStream(good));
                provenance(recovered, ModuleEntity.CompilerOrigin.DUMP, ModuleDumper.COMPILER_ABI_VERSION, ModuleDumper.COMPILER_ABI_VERSION);
                provenance(partial, ModuleEntity.CompilerOrigin.UNKNOWN, -1, -1);
            }
            int glnk=generatorFrameOffset(good),size=java.nio.ByteBuffer.wrap(good,glnk+8,4).getInt();
            int attributes=glnk+12+size;
            byte[] malformedAttributes=good.clone();malformedAttributes[attributes+7]++;
            try { dumper.load(new ByteArrayInputStream(malformedAttributes)); fail("Malformed ATMD accepted"); }
            catch (IOException expected) { }
            assertNotNull("ATMD corruption follows real function decoding",dumper.observed);
            provenance(dumper.observed.getModule(),ModuleEntity.CompilerOrigin.UNKNOWN,-1,-1);
            byte[] truncated = replaceEmptyTail(good, metadata(stamp, 1, 1, 7));
            try { dumper.load(new ByteArrayInputStream(Arrays.copyOf(truncated, reservedTailOffset(good) + 4 + metadata(stamp, 1, 1, 7).length - 1))); fail("Truncated metadata accepted"); }
            catch (EOFException expected) { }
            provenance(dumper.observed.getModule(), ModuleEntity.CompilerOrigin.UNKNOWN, -1, -1);
            // Keep the ABI20 declaration layout intact while contradicting its accepted header bound.
            byte[] higherThanHeader = replaceEmptyTail(good, metadata(stamp, 1, 1, ModuleDumper.COMPILER_ABI_VERSION + 1));
            try { dumper.load(new ByteArrayInputStream(higherThanHeader)); fail("Lineage exceeded accepted ABI"); }
            catch (IOException expected) { }
            provenance(dumper.observed.getModule(), ModuleEntity.CompilerOrigin.UNKNOWN, -1, -1);
        } finally { env.doFinal(); }
    }

    @Test public void unrelatedReservedPayloadIsOpaqueAndRemainsUncertified() throws Throwable {
        Environment env = environment(true);
        try {
            Context context = new Context("return 9;");
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            byte[] good = save(dumper, new JvmCompiler(env, context).compile(false));
            for (byte[] opaque : new byte[][] {{1}, {1, 2, 3}, metadata(0x11223344, 999, -20)}) {
                ModuleEntity module = dumper.load(new ByteArrayInputStream(replaceEmptyTail(good, opaque)));
                provenance(module, ModuleEntity.CompilerOrigin.DUMP, -1, ModuleDumper.COMPILER_ABI_VERSION);
                module = dumper.load(new ByteArrayInputStream(save(dumper, module)));
                provenance(module, ModuleEntity.CompilerOrigin.DUMP, -1, ModuleDumper.COMPILER_ABI_VERSION);
            }
        } finally { env.doFinal(); }
    }

    @Test public void invalidHeadersFailBeforeBodyAndFailedSourceStaysUnknown() throws Throwable {
        Environment env = environment(true);
        try {
            Context context = new Context("function provenance_header(){return 4;}return provenance_header();");
            ModuleDumper writer = new ModuleDumper(context, env, true);
            byte[] good = save(writer, new JvmCompiler(env, context).compile(false));
            ObservingDumper dumper = new ObservingDumper(context, env);
            for (int[] mutation : new int[][] {{0, 0}, {4, 99}, {8, -1}, {8, 0}, {8, ModuleDumper.COMPILER_ABI_VERSION + 1}, {12, 99}}) {
                byte[] bad = good.clone();
                int offset = mutation[0], value = mutation[1];
                for (int i = 0; i < 4; i++) bad[offset + i] = (byte) (value >>> (24 - i * 8));
                try { dumper.load(new ByteArrayInputStream(bad)); fail("Invalid header accepted"); }
                catch (IOException expected) { }
                assertNull("Body parser ran before header acceptance", dumper.observed);
            }
            try { dumper.load(new ByteArrayInputStream(Arrays.copyOf(good, 15))); fail("Truncated header accepted"); }
            catch (EOFException expected) { }
            assertNull(dumper.observed);
            ByteArrayInputStream input = new ByteArrayInputStream(good);
            assertEquals(PhpLanguageProfile.JPHP_LEGACY, ModuleDumper.readLanguageProfileHeader(input));
            assertEquals(good.length - 16, input.available());
            Context invalid = new Context("class BadProvenance{public int $x='bad';}return 1;");
            invalid.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            JvmCompiler compiler = new JvmCompiler(env, invalid);
            try { compiler.compile(false); fail("Invalid typed default compiled"); }
            catch (php.runtime.exceptions.support.ErrorException expected) { }
            provenance(compiler.getModule(), ModuleEntity.CompilerOrigin.UNKNOWN, -1, -1);
        } finally { env.doFinal(); }
    }
}
