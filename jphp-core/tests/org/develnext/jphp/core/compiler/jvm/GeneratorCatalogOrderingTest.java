package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.Generator;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.GeneratorEntity;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Generator table ordinals address real factories, not compilation or loaded class-id order. */
public class GeneratorCatalogOrderingTest extends JvmCompilerCase {
    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/generators/php_generator_ordering" + name)) {
            assertNotNull(input); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private ModuleEntity compile(Environment env, String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Context context = new Context(source.startsWith("<?php") ? source.substring(5) : source);
        context.setPhpLanguageProfile(profile);
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        List<String> names = catalog(module);
        for (int at = 0; at < names.size(); at++) {
            GeneratorEntity generator = module.findGenerator(at);
            assertEquals("source lexical id", at, generator.getId());
            assertEquals(module.getInternalName() + "_generator" + at, generator.getCompiledInternalName());
            assertSame(module, generator.getModule());
        }
        if (dumped) {
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); dumper.save(module, bytes);
            // Guarantee loader class ids differ from source lexical generator ordinals.
            for (int at = 0; at < 100; at++) env.getScope().nextClassIndex();
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals("saved table ordering must survive PHB", names, catalog(module));
            for (int at = 0; at < names.size(); at++) {
                assertNotEquals("loaded id is not a lexical ordinal", (long) at, module.findGenerator(at).getId());
                assertSame(module, module.findGenerator(at).getModule());
            }
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        return module;
    }
    private List<String> catalog(ModuleEntity module) {
        List<String> result = new ArrayList<>();
        for (GeneratorEntity generator : module.getGenerators()) result.add(generator.getCompiledInternalName());
        return result;
    }
    private GeneratorEntity exactFactory(Memory memory, ModuleEntity expectedModule) {
        Generator object = memory.toObject(Generator.class);
        GeneratorEntity reflection = (GeneratorEntity) object.getReflection();
        assertSame("factory retains its module", expectedModule, reflection.getModule());
        int found = -1, at = 0;
        for (GeneratorEntity generator : expectedModule.getGenerators()) {
            if (generator == reflection) { assertEquals("only one canonical pointer", -1, found); found = at; }
            at++;
        }
        assertTrue("factory metadata belongs to its exact table", found >= 0);
        assertSame(reflection, expectedModule.findGenerator(found));
        assertSame("metadata native class is the real object class", object.getClass(), reflection.getNativeClass());
        assertEquals("physical generator class", object.getClass().getName().replace('.', '/'),
                reflection.getCompiledInternalName().replace('.', '/'));
        return reflection;
    }
    private void mixed(boolean dumped) throws Throwable {
        String expected = resource(".expected");
        List<String> failures = new ArrayList<>();
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false,true}) {
            var scope = newScope(); scope.setDebugMode(debug);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); Environment env = new Environment(scope, output);
            try {
                String source = resource(profile == PhpLanguageProfile.JPHP_LEGACY ? "_legacy.php" : ".php");
                ModuleEntity module = compile(env, source, profile, dumped);
                assertEquals("eight lexical generator declarations, with one trait alias", 8, module.getGenerators().size());
                if (!dumped) {
                    var trait = module.findClass("OrderingTrait").findMethod("traitgenerator").getGeneratorEntity();
                    assertNotNull(trait);
                    // Modern consumers receive their trait methods when relations bind at execution.
                    if (profile == PhpLanguageProfile.JPHP_LEGACY) {
                        assertSame(trait, module.findClass("OrderingConsumer").findMethod("traitgenerator").getGeneratorEntity());
                        assertSame(trait, module.findClass("OrderingConsumer").findMethod("aliasgenerator").getGeneratorEntity());
                    }
                }
                ArrayMemory objects = module.include(env).toValue(ArrayMemory.class);
                env.getDefaultBuffer().flush(); assertEquals(expected, output.toString(StandardCharsets.UTF_8));
                assertSame(exactFactory(objects.valueOfIndex("trait"), module), exactFactory(objects.valueOfIndex("trait-alias"), module));
                for (String label : new String[]{"function-before","class-instance","class-static","deferred","closure","function-after","reference"})
                    exactFactory(objects.valueOfIndex(label), module);
                var consumer = env.fetchClass("OrderingConsumer", false);
                if (!dumped) assertSame(consumer.findMethod("traitgenerator").getGeneratorEntity(), consumer.findMethod("aliasgenerator").getGeneratorEntity());
                Generator reference = objects.valueOfIndex("reference").toObject(Generator.class);
                assertTrue(reference.isReturnReferences());
                assertFalse(objects.valueOfIndex("closure").toObject(Generator.class).isReturnReferences());
            } catch (Throwable failure) { failures.add(profile + " debug=" + debug + " dumped=" + dumped + " " + failure); }
            finally { env.doFinal(); }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }
    @Test public void lexicalMixedFactoriesExecuteWithExactMetadataFromSource() throws Throwable { mixed(false); }
    @Test public void lexicalMixedFactoriesExecuteWithExactMetadataFromPhb() throws Throwable { mixed(true); }

    private void driveOne(Environment env, Memory memory, String label, StringBuilder output, ModuleEntity expectedModule) throws Throwable {
        exactFactory(memory, expectedModule); Generator generator = memory.toObject(Generator.class);
        output.append(label).append(':').append(generator.current(env)).append(':')
                .append(generator.send(env, php.runtime.memory.LongMemory.valueOf(7))).append(':');
        generator.next(env);
        output.append(generator.valid(env).toBoolean() ? "valid" : "done").append(':').append(generator.getReturn(env)).append('\n');
    }
    private void crossModule(boolean donorDumped) throws Throwable {
        String expected = resource("_cross.expected"); List<String> failures = new ArrayList<>();
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false,true})
                for (boolean consumerDumped : new boolean[]{false,true}) {
            var scope = newScope(); scope.setDebugMode(debug);
            Environment env = new Environment(scope, new ByteArrayOutputStream());
            try {
                ModuleEntity donor = compile(env, resource("_donor.php"), profile, donorDumped);
                assertEquals(2, donor.getGenerators().size());
                ArrayMemory donorObjects = donor.include(env).toValue(ArrayMemory.class);
                GeneratorEntity borrowed = exactFactory(donorObjects.valueOfIndex(0), donor);
                Class<?> donorNativeClass = borrowed.getNativeClass(); List<String> donorNames = catalog(donor);
                MethodEntity donorMethod = env.fetchClass("ExternalOrderingTrait", false).findMethod("externalgenerator");
                // Historical dumps remain unavailable; validated current PHBs retain the exact donor pointer.
                if (donorDumped && !donor.hasValidatedGeneratorLinkage()) assertNull(donorMethod.getGeneratorEntity());
                else assertSame(borrowed, donorMethod.getGeneratorEntity());
                ModuleEntity consumer = compile(env, resource("_consumer.php"), profile, consumerDumped);
                assertSame(donor, borrowed.getModule()); assertSame(donorNativeClass, borrowed.getNativeClass());
                assertEquals(donorNames, catalog(donor)); assertEquals(3, consumer.getGenerators().size());
                for (GeneratorEntity local : consumer.getGenerators()) assertNotSame(borrowed, local);
                ArrayMemory consumerObjects = consumer.include(env).toValue(ArrayMemory.class);
                assertSame(borrowed, exactFactory(consumerObjects.valueOfIndex(0), donor));
                assertSame(borrowed, exactFactory(consumerObjects.valueOfIndex(1), donor));
                assertSame(donor, borrowed.getModule()); assertSame(donorNativeClass, borrowed.getNativeClass());
                if (!donorDumped && !consumerDumped) {
                    var consumerClass = env.fetchClass("ExternalOrderingConsumer", false);
                    assertSame(borrowed, consumerClass.findMethod("externalgenerator").getGeneratorEntity());
                    assertSame(borrowed, consumerClass.findMethod("externalalias").getGeneratorEntity());
                    assertSame(borrowed, donorMethod.getGeneratorEntity());
                }
                StringBuilder actual = new StringBuilder();
                for (int at = 0; at < donorObjects.size(); at++)
                    driveOne(env, donorObjects.valueOfIndex(at), "donor-" + at, actual, donor);
                for (int at = 0; at < consumerObjects.size(); at++)
                    driveOne(env, consumerObjects.valueOfIndex(at), "consumer-" + at, actual, at < 2 ? donor : consumer);
                assertEquals(expected, actual.toString());
            } catch (Throwable failure) { failures.add(profile + " debug=" + debug + " donorDumped=" + donorDumped + " consumerDumped=" + consumerDumped + " " + failure); }
            finally { env.doFinal(); }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }
    @Test public void importedTraitPreservesSourceDonorFactoryIdentity() throws Throwable { crossModule(false); }
    @Test public void importedTraitPreservesPhbDonorFactoryIdentity() throws Throwable { crossModule(true); }

    private void invokeCatalog(JvmCompiler compiler) throws Throwable {
        Method method = JvmCompiler.class.getDeclaredMethod("canonicalizeGeneratorCatalog"); method.setAccessible(true);
        try { method.invoke(compiler); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private GeneratorEntity fabricated(ModuleEntity module, int id) {
        GeneratorEntity generator = new GeneratorEntity(module.getContext()); generator.setId(id);
        generator.setInternalName(module.getInternalName() + "_generator" + id); return generator;
    }
    private void rejectedCatalog(int mode) throws Throwable {
        var scope = newScope(); Environment env = new Environment(scope, new ByteArrayOutputStream());
        try {
            JvmCompiler compiler = new JvmCompiler(env, new Context("return 1;")); ModuleEntity module = compiler.getModule();
            module.setInternalName("$ordering_guard"); GeneratorEntity first = fabricated(module, mode == 0 ? 1 : 0);
            module.addGenerator(first);
            if (mode == 1) module.addGenerator(fabricated(module,0));
            if (mode == 2) first.setInternalName("$foreign_generator0");
            if (mode == 3) module.setCompiledArtifact(true);
            List<GeneratorEntity> before = new ArrayList<>(module.getGenerators());
            try { invokeCatalog(compiler); fail("Malformed catalog accepted"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains(mode == 0 ? "gap" : mode == 1 ? "ordinal" : mode == 2 ? "identity" : "loaded")); }
            assertEquals(before.size(), module.getGenerators().size());
            int at = 0; for (GeneratorEntity after : module.getGenerators()) assertSame(before.get(at++), after);
        } finally { env.doFinal(); }
    }
    @Test public void malformedSourceCatalogsFailBeforePublicationWithoutPartialReorder() throws Throwable {
        for (int mode = 0; mode < 4; mode++) rejectedCatalog(mode);
    }
    @Test public void localDuplicatePointerIsIdempotentAndForeignPointerNeverChangesOwner() throws Throwable {
        Context context = new Context("return 1;"); ModuleEntity donor = new ModuleEntity(context), consumer = new ModuleEntity(context);
        donor.setInternalName("$donor_guard"); consumer.setInternalName("$consumer_guard");
        GeneratorEntity first = fabricated(donor,0), second = fabricated(donor,1);
        donor.addGenerator(first); donor.addGenerator(first); donor.addGenerator(second);
        assertEquals("GeneratorEntity.equals accepts every generator; dedup must use identity", 2, donor.getGenerators().size());
        assertSame(first, donor.findGenerator(0)); assertSame(second, donor.findGenerator(1));
        consumer.addGenerator(first); consumer.addGenerator(second);
        assertEquals(0, consumer.getGenerators().size()); assertSame(donor, first.getModule()); assertSame(donor, second.getModule());
    }
}
