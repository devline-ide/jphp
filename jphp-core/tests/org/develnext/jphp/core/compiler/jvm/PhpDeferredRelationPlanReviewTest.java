package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.PhpTraitCompositionPlan;
import php.runtime.reflection.ParameterEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.*;

/** Actual declaration-produced plans; no fabricated ClassEntity or loader witness. */
public class PhpDeferredRelationPlanReviewTest extends JvmCompilerCase {
    @Override protected CompileScope newScope() {
        var scope = super.newScope();
        scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        return scope;
    }

    private ModuleEntity compile(Environment env, String source, boolean phb) throws Throwable {
        var context = new Context(source.startsWith("<?php ") ? source.substring(6) : source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(false);
        if (phb) {
            var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module);
        return module;
    }

    private static void rejects(PhpTraitCompositionPlan plan, String label, Runnable poison, Runnable restore) {
        plan.validate();
        try {
            poison.run();
            try { plan.validate(); fail("Changed composition accepted: " + label); }
            catch (LinkageError expected) { }
        } finally {
            restore.run(); plan.validate();
        }
    }

    @Test(timeout = 60000) public void actualSourceAndDumpPlansRejectChangedSelectionAndParameterContracts() throws Throwable {
        for (boolean strict : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true})
            for (boolean phb : new boolean[]{false, true}) {
                var scope = newScope(); scope.setDebugMode(debug);
                var env = new Environment(scope, new ByteArrayOutputStream());
                try {
                    var module = compile(env, "<?php declare(strict_types=" + (strict ? 1 : 0) + ");"
                            + "function install(){trait ReviewDonor{public function m(int $x=3):int{return $x;}}"
                            + "class ReviewConsumer{use ReviewDonor{m as alias;}}}install();return 1;", phb);
                    env.registerModule(module); assertEquals(1, module.include(env).toLong());
                    var consumer = env.fetchClass("ReviewConsumer", false);
                    var plan = consumer.getClassComposition(); assertNotNull(plan); plan.validate();
                    var copy = plan.methods().stream().filter(c -> c.target().getName().equals("alias")).findFirst().orElseThrow();
                    var donor = copy.donor(); var target = copy.target();
                    var parameters = target.getParameters(); var parameter = parameters[0];
                    assertNotSame(donor.getParameters(), parameters);
                    assertNotSame(donor.getParameters()[0], parameter);
                    assertEquals(donor.getParameters()[0].getName(), parameter.getName());
                    assertEquals(donor.getParameters()[0].getDefaultValue().toLong(), parameter.getDefaultValue().toLong());
                    var internal = target.getInternalName();
                    rejects(plan, "target internal name", () -> target.setInternalName(internal + "$poison"), () -> target.setInternalName(internal));
                    var name = target.getName();
                    rejects(plan, "target PHP name", () -> target.setName(name + "Poison"), () -> target.setName(name));
                    var donorInternal = donor.getInternalName();
                    rejects(plan, "donor internal name", () -> donor.setInternalName(donorInternal + "$poison"), () -> donor.setInternalName(donorInternal));
                    boolean flag = target.isFinal();
                    rejects(plan, "target final", () -> target.setFinal(!flag), () -> target.setFinal(flag));
                    boolean ref = target.isReturnReference();
                    rejects(plan, "return reference", () -> target.setReturnReference(!ref), () -> target.setReturnReference(ref));
                    boolean nullable = target.isReturnTypeNullable();
                    rejects(plan, "return nullability", () -> target.setReturnTypeNullable(!nullable), () -> target.setReturnTypeNullable(nullable));
                    var returnType = target.getReturnTypeChecker(); assertNotNull(returnType);
                    rejects(plan, "return type", () -> target.setReturnTypeChecker(null), () -> target.setReturnTypeChecker(returnType));
                    rejects(plan, "parameter array", () -> target.setParameters(parameters.clone()), () -> target.setParameters(parameters));
                    rejects(plan, "parameter entity", () -> parameters[0] = donor.getParameters()[0], () -> parameters[0] = parameter);
                    var parameterName = parameter.getName();
                    rejects(plan, "parameter name", () -> parameter.setName(parameterName + "Poison"), () -> parameter.setName(parameterName));
                    boolean parameterRef = parameter.isReference();
                    rejects(plan, "parameter reference", () -> parameter.setReference(!parameterRef), () -> parameter.setReference(parameterRef));
                    var defaultValue = parameter.getDefaultValue();
                    rejects(plan, "default value", () -> parameter.setDefaultValue(Memory.NULL), () -> parameter.setDefaultValue(defaultValue));
                    var constant = parameter.getDefaultValueConstName();
                    rejects(plan, "default constant", () -> parameter.setDefaultValueConstName("POISON_CONSTANT"), () -> parameter.setDefaultValueConstName(constant));
                    var initializer = parameter.getDefaultInitializer();
                    rejects(plan, "default initializer", () -> parameter.setDefaultInitializer(new ParameterEntity.DefaultInitializer(
                            "poison-module", "poison.Type", "__$parameterDefaultPoison", consumer.getName())),
                            () -> { parameter.setDefaultInitializer(initializer); parameter.setDefaultValue(defaultValue); });
                    var nativeMethod = target.getNativeMethod(); assertNotNull(nativeMethod);
                    var unrelated = Object.class.getDeclaredMethod("toString");
                    rejects(plan, "physical member", () -> target.setNativeMethod(unrelated), () -> target.setNativeMethod(nativeMethod));
                    var bytes = consumer.getData(); var altered = bytes.clone(); altered[altered.length - 1] ^= 1;
                    rejects(plan, "owner template bytes", () -> consumer.setData(altered), () -> consumer.setData(bytes));
                    var donorBytes = donor.getClazz().getData(); var donorAltered = donorBytes.clone(); donorAltered[donorAltered.length - 1] ^= 1;
                    rejects(plan, "donor template bytes", () -> donor.getClazz().setData(donorAltered), () -> donor.getClazz().setData(donorBytes));
                    assertEquals(3, target.getParameters()[0].getDefaultValue().toLong());
                } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
            }
    }

    @Test(timeout = 60000) public void finalTraitAliasHasActualFinalJvmMemberSourceAndDump() throws Throwable {
        for (boolean strict : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true})
            for (boolean phb : new boolean[]{false, true}) {
                var scope = newScope(); scope.setDebugMode(debug); var env = new Environment(scope, new ByteArrayOutputStream());
                try {
                    var module = compile(env, "<?php declare(strict_types=" + (strict ? 1 : 0) + ");"
                            + "function install(){trait FinalDonor{function m(){return 1;}}"
                            + "class FinalConsumer{use FinalDonor{m as final alias;}}}install();return 1;", phb);
                    env.registerModule(module); assertEquals(1, module.include(env).toLong());
                    var owner = env.fetchClass("FinalConsumer", false); var target = owner.findMethod("alias");
                    assertTrue(target.isFinal()); assertNotNull(target.getNativeMethod());
                    assertSame(owner.getNativeClass(), target.getNativeMethod().getDeclaringClass());
                    assertTrue(java.lang.reflect.Modifier.isFinal(target.getNativeMethod().getModifiers()));
                    owner.getClassComposition().validate();
                } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
            }
    }

    @Test(timeout = 60000) public void nativePinnedAliasConsumerInheritanceAndReuseStaticStorageSourceAndDump() throws Throwable {
        var input = getClass().getResourceAsStream("/resources/functions/php85_deferred_trait_static_storage.tsv");
        if (input == null) input = getClass().getResourceAsStream("/functions/php85_deferred_trait_static_storage.tsv");
        assertNotNull(input); int count = 0;
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var cells = line.split("\\t", -1); assertEquals(4, cells.length); count++;
                String source = new String(Base64.getDecoder().decode(cells[2]), StandardCharsets.UTF_8);
                String expected = new String(Base64.getDecoder().decode(cells[3]), StandardCharsets.UTF_8);
                for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                    var scope = newScope(); scope.setDebugMode(debug); var output = new ByteArrayOutputStream(); var env = new Environment(scope, output);
                    try {
                        var module = compile(env, "<?php " + source, phb); env.registerModule(module); module.include(env); env.doFinal();
                        assertEquals(cells[0] + " strict=" + cells[1] + " debug=" + debug + " PHB=" + phb,
                                expected, output.toString(StandardCharsets.UTF_8));
                    } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
                }
            }
        }
        assertEquals(6, count);
    }
}
