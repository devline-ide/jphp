package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.PhpObjectShellVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Generated destructor bodies execute through Authority with a borrowed FINALIZING receiver. */
public class PhpRuntimeDestructorReceiverCompilerTest extends JvmCompilerCase {
    @FunctionalInterface private interface Work { void run() throws Throwable; }
    private void reject(Work work) throws Throwable {
        try { work.run(); fail("Expected lifecycle rejection before effects"); }
        catch (IllegalArgumentException | IllegalStateException expected) {}
        catch (IOException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage(), expected.getMessage().contains("Lifecycle receiver"));
        }
    }
    private final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Environment env;
        final ClassEntity entity;
        final PhpOwnershipDomain domain = new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority;
        final PhpOwnershipDomain.Execution execution;
        Fixture(boolean debug, boolean dumped, boolean strict, String body) throws Throwable {
            var scope = newScope();
            scope.setDebugMode(debug);
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            env = new Environment(scope, output);
            env.getDefaultBuffer().setChunkSize(0);
            var context = new Context((strict ? "declare(strict_types=1);" : "")
                    + "class RDtor {public int $v=7;function other(){echo 'other';}function __destruct(){" + body + "}}");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module = new JvmCompiler(env, context).compile(false);
            if (dumped) {
                var bytes = new ByteArrayOutputStream();
                var dumper = new ModuleDumper(context, env, true);
                dumper.save(module, bytes);
                module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            scope.loadModule(module);
            scope.addUserModule(module);
            env.registerModule(module);
            module.include(env);
            entity = env.fetchClass("RDtor", true, PhpLanguageProfile.PHP_8_5);
            authority = new PhpRuntimeOwnership.Authority(env, domain);
            execution = authority.enter();
        }
        String text() throws Throwable { env.getDefaultBuffer().flush(); return output.toString(StandardCharsets.UTF_8); }
        public void close() throws Exception {
            try { authority.close(); }
            finally {
                execution.close();
                try { env.doFinal(); }
                catch (Exception | Error failure) { throw failure; }
                catch (Throwable failure) { throw new AssertionError(failure); }
            }
        }
    }

    private void actualBody(String body, int receiverImports) throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true})
            for (boolean strict : new boolean[]{false, true}) try (var f = new Fixture(debug, dumped, strict, body)) {
                var method = f.entity.methodDestruct;
                var member = method.getNativeMethod();
                var runtime = PhpRuntimeOwnership.readRuntimePlan(f.entity.getData(), member.getName(), PhpOwnershipExecution.descriptor(member));
                assertTrue(runtime.lifecycle());
                assertEquals(receiverImports, runtime.lifecycleReceivers().size());
                assertTrue(runtime.unsupported().toString(), runtime.unsupported().isEmpty());
                f.authority.registerMethod(method, f.entity.getData(), (p, m, b) -> {});
                PhpOwnershipObjects.ObjectCertificate[] certificate = {null};
                PhpOwnershipObjects.ExecutionEntry[] saved = {null};
                int[] calls = {0};
                var protocol = new PhpOwnershipObjects.CompleteDestructorProtocol() {
                    public void validateMember(ClassEntity entity, MethodEntity exact, Environment env) {
                        assertSame(f.entity, entity); assertSame(method, exact); assertSame(f.env, env);
                        f.authority.validateDestructor(entity, exact);
                    }
                    public void invoke(PhpOwnershipObjects.ExecutionEntry entry, Environment env, TraceInfo trace) throws Throwable {
                        saved[0] = entry; calls[0]++;
                        var cert = certificate[0];
                        assertEquals(PhpOwnership.State.FINALIZING, cert.node().state());
                        assertEquals(0, cert.node().incoming());
                        assertTrue(cert.properties().table().getByScalar("v").hasPropertySources());
                        // The ordinary method path would add an owning receiver and must reject first.
                        reject(() -> f.authority.invoke(method, entry.receiver(), trace));
                        assertEquals("", f.text());
                        f.authority.invokeDestructor(entry, trace);
                        assertEquals("d", f.text());
                        assertEquals(0, cert.node().incoming());
                        assertTrue(cert.properties().table().getByScalar("v").hasPropertySources());
                        reject(() -> entry.consume(f.domain, env, method));
                        reject(() -> f.authority.invokeDestructor(entry, trace));
                        assertEquals("d", f.text());
                    }
                };
                var plan = PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity, f.env, method, protocol);
                var objects = f.authority.objects();
                var provider = plan.initializeAndRegister(objects);
                var allocation = objects.allocate(provider, f.env, plan.certifiedDefaults(objects));
                certificate[0] = allocation.certificate();
                var node = certificate[0].node();
                var cell = certificate[0].properties().table().getByScalar("v");
                int depth = f.env.getCallStack().getTop();
                allocation.close();
                assertEquals(1, calls[0]);
                assertEquals(depth, f.env.getCallStack().getTop());
                assertFalse(cell.hasPropertySources());
                assertEquals(PhpOwnership.State.DISPOSED, node.state());
                assertEquals(0, f.domain.graph().liveNodes());
                reject(() -> saved[0].receiver());
                assertNull(f.authority.currentFrame());
            }
    }

    @Test public void literalDestructorConsumesTicketWithoutAThisLocal() throws Throwable { actualBody("echo 'd';", 0); }
    @Test public void exactThisDiscardImportsReceiverWithoutOwningIt() throws Throwable { actualBody("$this;echo 'd';", 1); }

    @Test public void unprovedFieldAndWholeReceiverTransfersRejectBeforeEffects() throws Throwable {
        for (String body : new String[]{"echo $this->v;", "$copy=$this;echo 'bad';", "return $this;"})
            for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true})
                try (var f = new Fixture(debug, dumped, false, body)) {
                    reject(() -> f.authority.registerMethod(f.entity.methodDestruct, f.entity.getData(), (p, m, b) -> {}));
                    assertEquals("", f.text());
                    assertEquals(0, f.domain.graph().liveNodes());
                }
    }

    @Test public void unregisteredMethodCannotInitializeLifecyclePlanOrAllocate() throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true})
            try (var f = new Fixture(debug, dumped, false, "echo 'bad';")) {
                var method = f.entity.methodDestruct;
                boolean initialized = f.entity.isRuntimeStateInitialized(f.env);
                var protocol = new PhpOwnershipObjects.CompleteDestructorProtocol() {
                    public void validateMember(ClassEntity entity, MethodEntity exact, Environment env) { f.authority.validateDestructor(entity, exact); }
                    public void invoke(PhpOwnershipObjects.ExecutionEntry entry, Environment env, TraceInfo trace) { fail("Unregistered body ran"); }
                };
                reject(() -> PhpObjectShellVerifier.prepareLifecycleDefaults(f.entity, f.env, method, protocol));
                assertEquals(initialized, f.entity.isRuntimeStateInitialized(f.env));
                assertEquals("", f.text());
                assertEquals(0, f.domain.graph().liveNodes());
            }
    }
}
