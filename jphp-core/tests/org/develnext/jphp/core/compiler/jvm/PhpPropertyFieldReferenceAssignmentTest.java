package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.PhpOwnershipDomain;
import php.runtime.PhpOwnershipExecution;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import static org.junit.Assert.*;

/** PHP 8.5.11 FIELD-to-FIELD binding oracle; ordinary execution and honest certification debt. */
public class PhpPropertyFieldReferenceAssignmentTest extends JvmCompilerCase {
    private interface Check {
        void run(Environment env, ModuleEntity module, String mode) throws Throwable;
    }

    private void modes(String source, Check check) throws Throwable {
        for (boolean debug : new boolean[]{false, true})
            for (boolean dumped : new boolean[]{false, true})
                for (boolean strict : new boolean[]{false, true}) {
                    var scope = newScope();
                    scope.setDebugMode(debug);
                    scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                    var env = new Environment(scope, new ByteArrayOutputStream());
                    try {
                        var context = new Context((strict ? "declare(strict_types=1);" : "") + source);
                        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module = new JvmCompiler(env, context).compile(false);
                        if (dumped) {
                            var dumper = new ModuleDumper(context, env, true);
                            var bytes = new ByteArrayOutputStream();
                            dumper.save(module, bytes);
                            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        scope.loadModule(module);
                        scope.addUserModule(module);
                        env.registerModule(module);
                        check.run(env, module, "debug=" + debug + " dump=" + dumped + " strict=" + strict);
                    } finally {
                        env.doFinal();
                        scope.getClassLoader().close();
                    }
                }
    }

    private PhpRuntimeOwnership.RuntimePlan plan(ModuleEntity module) throws IOException {
        var member = module.getNativeMethod();
        var plan = PhpRuntimeOwnership.readRuntimePlan(module.getData(), member.getName(),
                PhpOwnershipExecution.descriptor(member));
        assertNotNull("Actual module member lacks ordinary metadata", plan);
        return plan;
    }

    private void ordinary(String source, String expected) throws Throwable {
        modes(source, (env, module, mode) -> {
            module.include(env);
            assertEquals(mode, expected, env.getDefaultBuffer().getContents().toString());
        });
    }

    @Test public void directBindingKeepsAliasingAndEveryLiveTypedGuard() throws Throwable {
        ordinary("class A{public int $v=1;}$a=new A;$b=new A;$b->v=&$a->v;"
                        + "$b->v=7;echo serialize([$a->v,$b->v]);",
                "a:2:{i:0;i:7;i:1;i:7;}");
        ordinary("class A{public ?int $v=null;}class B{public ?string $v=null;}$a=new A;$b=new B;"
                        + "$b->v=&$a->v;try{$a->v=1;}catch(TypeError $e){echo 'int-rejected|';}"
                        + "try{$b->v='x';}catch(TypeError $e){echo 'string-rejected|';}"
                        + "echo serialize([$a->v,$b->v]);",
                "int-rejected|string-rejected|a:2:{i:0;N;i:1;N;}");
        ordinary("class A{public int $v=1;}class B{public string $v='s';}$a=new A;$b=new B;"
                        + "try{$b->v=&$a->v;}catch(TypeError $e){echo 'rejected|';}"
                        + "echo serialize([$a->v,$b->v]);",
                "rejected|a:2:{i:0;i:1;i:1;s:1:\"s\";}");
    }

    @Test public void intermediateAndLaterCvReferencesRetainActualPublicationProof() throws Throwable {
        ordinary("class A{public int $v=1;}$a=new A;$b=new A;$r=&$a->v;$b->v=&$r;"
                        + "$r=4;echo serialize([$a->v,$b->v,$r]);",
                "a:3:{i:0;i:4;i:1;i:4;i:2;i:4;}");
        modes("class A{public int $v=1;}$a=new A;$b=new A;$b->v=&$a->v;"
                + "$r=&$a->v;$r=9;echo serialize([$a->v,$b->v,$r]);", (env, module, mode) -> {
            var plan = plan(module);
            assertTrue(mode + plan.unsupported(), plan.unsupported().contains("property-reference-field-binding"));
            assertEquals(mode, 1, plan.propertyReferences().size());
            var field = plan.propertyReferences().get(0);
            var producers = plan.producers().stream().filter(p -> p.kind().equals("PROPERTY_REF_RESULT")).toList();
            assertEquals(mode, 1, producers.size());
            assertEquals(mode, field.id(), producers.get(0).id());
            assertTrue(mode + producers.get(0).consumers(), producers.get(0).consumers().contains("CV"));
            assertTrue(mode, field.resultPublication() > field.helper());
            module.include(env);
            assertEquals(mode, "a:3:{i:0;i:9;i:1;i:9;i:2;i:9;}", env.getDefaultBuffer().getContents().toString());
        });
    }

    @Test public void unsupportedDirectBindingCertificationRejectsBeforeBodyEffects() throws Throwable {
        modes("class A{public int $v=1;}echo 'effect';$a=new A;$b=new A;$b->v=&$a->v;return 7;",
                (env, module, mode) -> {
                    var plan = plan(module);
                    assertTrue(mode + plan.unsupported(), plan.unsupported().contains("property-reference-field-binding"));
                    assertTrue(mode, plan.propertyReferences().isEmpty());
                    assertEquals(mode, 0, plan.producers().stream().filter(p -> p.kind().equals("PROPERTY_REF_RESULT")).count());
                    env.getDefaultBuffer().setChunkSize(0);
                    var domain = new PhpOwnershipDomain();
                    var authority = new PhpRuntimeOwnership.Authority(env, domain);
                    try (var execution = authority.enter(); var owned = authority) {
                        boolean rejected = false;
                        try {
                            authority.registerModule(module, module.getData(), (p, m, bytes) -> {
                                assertEquals(module.getNativeMethod(), m);
                                assertArrayEquals(module.getData(), bytes);
                                assertEquals(PhpOwnershipExecution.EntryKind.MODULE, p.entryKind());
                            });
                        } catch (IllegalArgumentException | IllegalStateException | IOException expected) {
                            assertTrue(mode + expected, expected.getMessage().contains("property-reference-field-binding"));
                            rejected = true;
                        }
                        assertTrue(mode + " admitted an incomplete FIELD-to-FIELD plan", rejected);
                        assertEquals(mode, "", env.getDefaultBuffer().getContents().toString());
                        assertFalse(mode, module.findClass("A").isRuntimeStateInitialized(env));
                        assertEquals(mode, 0, domain.graph().liveNodes());
                    }
                    assertEquals(mode, 0, domain.graph().liveNodes());
                });
    }
}
