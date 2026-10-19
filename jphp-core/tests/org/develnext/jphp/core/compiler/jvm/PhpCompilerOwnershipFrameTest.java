package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.*;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;

/** Actual compiled body/CV publications. The host explicitly admits exact members; no automatic body certification. */
public class PhpCompilerOwnershipFrameTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Environment env;
        final ModuleEntity module;
        final PhpOwnershipDomain domain = new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;
        final PhpOwnershipExecution.Session session;
        final PhpOwnershipExecution.Scope scope;
        Fixture(boolean debug, boolean dumped, String source) throws Throwable {
            var compileScope = newScope(); compileScope.setDebugMode(debug);
            env = new Environment(compileScope, output); env.getDefaultBuffer().setChunkSize(0); Context context = new Context(source);
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ModuleEntity compiled = new JvmCompiler(env, context).compile(false);
            if (dumped) { var dump = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream(); dump.save(compiled, bytes); compiled = dump.load(new ByteArrayInputStream(bytes.toByteArray())); }
            module = compiled;
            env.scope.loadModule(module); env.scope.addUserModule(module); env.registerModule(module); module.include(env);
            participant = domain.participant(env); session = new PhpOwnershipExecution.Session(participant, env); scope = session.enter();
        }
        OwnershipFrameCompiler.Plan plan(byte[] bytes, Method member) throws IOException {
            return OwnershipFrameCompiler.read(bytes, member.getName(), PhpOwnershipExecution.descriptor(member));
        }
        void admit(Object entity, Method member, byte[] bytes) throws Throwable {
            var description = plan(bytes, member); assertNotNull(description); assertEquals(List.of(), description.unsupported());
            StringWriter diagnostics = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(bytes), env.scope.getClassLoader(), false, new PrintWriter(diagnostics));
            assertEquals("ASM", "", diagnostics.toString());
            session.registerGenerated(entity, member, bytes, description.runtimePlan(), (actual, method, defined) -> {
                var reparsed = plan(defined, method); assertEquals(description, reparsed);
                assertTrue(actual.requiredCapabilities().stream().allMatch(c -> Set.of("frame-local-publication-v1", "empty-tick-handler", "literal-buffered-output").contains(c) || c.startsWith("function-call:")));
            });
        }
        FunctionEntity function(String name) { return env.fetchFunction(name); }
        void admit(String name) throws Throwable { var f = function(name); admit(f, f.getNativeMethod(), f.getData()); }
        String text() throws Throwable { env.getDefaultBuffer().flush(); return output.toString(StandardCharsets.UTF_8); }
        public void close() throws Exception { scope.close(); participant.close(); try {env.doFinal();} catch (Exception | Error e) {throw e;} catch (Throwable e) {throw new AssertionError(e);} }
    }

    @Test public void actualCvReplacementDisposesImportedObjectInBothCompilerModesAndDump() throws Throwable {
        String source = "class ExecutionChild {function __destruct(){echo 'D';}} function release_cv($v){$a=$v;$v=null;$a=null;return 7;} return 0;";
        for (boolean debug : new boolean[] {false, true}) for (boolean dumped : new boolean[] {false, true})
            try (var f = new Fixture(debug, dumped, source)) {
                f.admit("release_cv"); var clazz = f.env.fetchClass("ExecutionChild"); var dtor = clazz.findMethod("__destruct");
                f.admit(dtor, dtor.getNativeMethod(), clazz.getData());
                var objects = f.session.objects(); var provider = objects.registerPropertiesProvider(clazz, clazz.getNativeClass().getConstructor(Environment.class, ClassEntity.class), (e, o) -> {});
                var allocation = objects.allocate(provider, f.env, new IdentityHashMap<>()); var node = allocation.binding().node();
                try (var roots = f.session.newCallRoots()) {
                    roots.send(allocation.binding()); allocation.close(); assertEquals(PhpOwnership.State.LIVE, node.state());
                    assertEquals(7, roots.invokeFunction(f.function("release_cv"), TraceInfo.UNKNOWN).toLong());
                    assertEquals(PhpOwnership.State.DISPOSED, node.state()); assertEquals("D", f.text());
                }
            }
    }

    @Test public void pendingReturnOutlivesCalleeLocalsAndMovesToCallerResult() throws Throwable {
        String source = "class ExecutionReturn {function __destruct(){echo 'R';}} function return_cv($v){$a=$v;$v=null;return $a;} return 0;";
        for (boolean debug : new boolean[] {false, true}) for (boolean dumped : new boolean[] {false, true})
            try (var f = new Fixture(debug, dumped, source)) {
                f.admit("return_cv"); var clazz = f.env.fetchClass("ExecutionReturn"); var dtor = clazz.findMethod("__destruct"); f.admit(dtor, dtor.getNativeMethod(), clazz.getData());
                var objects = f.session.objects(); var provider = objects.registerPropertiesProvider(clazz, clazz.getNativeClass().getConstructor(Environment.class, ClassEntity.class), (e, o) -> {});
                var allocation = objects.allocate(provider, f.env, new IdentityHashMap<>()); var object = allocation.object(); var node = allocation.binding().node();
                try (var roots = f.session.newCallRoots()) {
                    roots.send(allocation.binding()); allocation.close();
                    Memory result = roots.invokeFunction(f.function("return_cv"), TraceInfo.UNKNOWN);
                    assertSame(object, result.toValue(php.runtime.memory.ObjectMemory.class).value);
                    assertEquals(PhpOwnership.State.LIVE, node.state()); assertEquals(1, node.incoming()); assertEquals("", f.text());
                }
                assertEquals(PhpOwnership.State.DISPOSED, node.state()); assertEquals("R", f.text());
            }
    }

    @Test public void arrayValueCopiesAndTypedReturnKeepBackingStorageUntilCallerRelease() throws Throwable {
        String source = "class ExecutionArrayChild {function __destruct(){echo 'A';}} function array_return_cv($v):array{$a=$v;$v=null;return $a;} return 0;";
        for (boolean debug : new boolean[] {false, true}) for (boolean dumped : new boolean[] {false, true})
            try (var f = new Fixture(debug, dumped, source)) {
                f.admit("array_return_cv"); var clazz = f.env.fetchClass("ExecutionArrayChild"); var dtor = clazz.findMethod("__destruct");
                f.admit(dtor, dtor.getNativeMethod(), clazz.getData());
                var objects = f.session.objects(); var provider = objects.registerPropertiesProvider(clazz, clazz.getNativeClass().getConstructor(Environment.class, ClassEntity.class), (e, o) -> {});
                var child = objects.allocate(provider, f.env, new IdentityHashMap<>()); var childNode = child.binding().node();
                var array = f.session.arrays().allocate(false); array.append(child.binding()); child.close(); var storage = array.binding().node();
                try (var roots = f.session.newCallRoots()) {
                    roots.send(array.binding()); array.close();
                    Memory result = roots.invokeFunction(f.function("array_return_cv"), TraceInfo.UNKNOWN);
                    assertTrue(result.isArray()); assertEquals(1, result.toValue(php.runtime.memory.ArrayMemory.class).size());
                    assertEquals(PhpOwnership.State.LIVE, storage.state()); assertEquals(1, storage.incoming());
                    assertEquals(PhpOwnership.State.LIVE, childNode.state()); assertEquals("", f.text());
                }
                assertEquals(PhpOwnership.State.DISPOSED, storage.state()); assertEquals(PhpOwnership.State.DISPOSED, childNode.state()); assertEquals("A", f.text());
            }
    }

    @Test public void nestedSendAndEvalMoveActualResultBeforeTemporaryPacketCleanup() throws Throwable {
        String source = "class ExecutionCallChild {function __destruct(){echo 'D';}} function call_identity($v){return $v;} function call_marker(){echo 'M';return 8;} function call_take($a,$b){echo 'T';return $a;} function call_caller($v){$a=call_take(call_identity($v),call_marker());$v=null;$a=null;echo 'A';return 9;} return 0;";
        for (boolean debug : new boolean[] {false, true}) for (boolean dumped : new boolean[] {false, true})
            try (var f = new Fixture(debug, dumped, source)) {
                for (String name : List.of("call_identity", "call_marker", "call_take", "call_caller")) f.admit(name);
                var caller = f.function("call_caller"); assertEquals(3, f.plan(caller.getData(), caller.getNativeMethod()).eval().size());
                var clazz = f.env.fetchClass("ExecutionCallChild"); var dtor = clazz.findMethod("__destruct"); f.admit(dtor, dtor.getNativeMethod(), clazz.getData());
                var objects = f.session.objects(); var provider = objects.registerPropertiesProvider(clazz, clazz.getNativeClass().getConstructor(Environment.class, ClassEntity.class), (e,o) -> {});
                var allocation = objects.allocate(provider, f.env, new IdentityHashMap<>()); var node = allocation.binding().node();
                try (var roots = f.session.newCallRoots()) {
                    roots.send(allocation.binding()); allocation.close();
                    assertEquals(9, roots.invokeFunction(caller, TraceInfo.UNKNOWN).toLong());
                    assertEquals(PhpOwnership.State.DISPOSED, node.state()); assertEquals("MTDA", f.text());
                }
            }
    }

    @Test public void nativeCountAndSerializeUseActualPacketsAndUnknownNativeRejectsBeforeEffects() throws Throwable {
        String source = "class ExecutionNativeChild {function __destruct(){echo 'N';}} function native_cv($v){$n=count($v);$s=serialize($v);$v=null;echo 'A';return $s;} function native_unknown($v){echo 'B';return strlen($v);} return 0;";
        for (boolean debug : new boolean[] {false, true}) for (boolean dumped : new boolean[] {false, true})
            try (var f = new Fixture(debug, dumped, source)) {
                f.admit("native_cv"); var function = f.function("native_cv"); var plan = f.plan(function.getData(), function.getNativeMethod());
                assertTrue(plan.required().contains("function-call:count:1")); assertTrue(plan.required().contains("function-call:serialize:1"));
                var unknown = f.function("native_unknown"); var unsupported = f.plan(unknown.getData(), unknown.getNativeMethod());
                assertThrows(IllegalArgumentException.class, () -> f.session.registerGenerated(unknown,unknown.getNativeMethod(),unknown.getData(),unsupported.runtimePlan(),(p,m,b) -> {})); assertEquals("", f.text());
                var clazz = f.env.fetchClass("ExecutionNativeChild"); var dtor = clazz.findMethod("__destruct"); f.admit(dtor, dtor.getNativeMethod(), clazz.getData());
                var objects = f.session.objects(); var provider = objects.registerPropertiesProvider(clazz, clazz.getNativeClass().getConstructor(Environment.class, ClassEntity.class), (e,o) -> {});
                var child = objects.allocate(provider, f.env, new IdentityHashMap<>()); var childNode = child.binding().node();
                var array = f.session.arrays().allocate(false); array.append(child.binding()); child.close(); var storage = array.binding().node();
                try (var roots = f.session.newCallRoots()) {
                    roots.send(array.binding()); array.close();
                    assertEquals("a:1:{i:0;O:20:\"ExecutionNativeChild\":0:{}}", roots.invokeFunction(function, TraceInfo.UNKNOWN).toString());
                    assertEquals(PhpOwnership.State.DISPOSED, storage.state()); assertEquals(PhpOwnership.State.DISPOSED, childNode.state()); assertEquals("NA", f.text());
                }
            }
    }

    @Test public void typedReturnFailureRetainsCurrentTraceArgumentsUntilFailurePacketRelease() throws Throwable {
        for (boolean replaced : new boolean[] {false, true}) {
            String body = replaced ? "$v=$a;$a=null;return $v;" : "return $a;";
            String source = "class ExecutionBadChild {function __destruct(){echo 'D';}} function bad_cv($a):int{echo 'B';" + body + "} return 0;";
            for (boolean debug : new boolean[] {false, true}) for (boolean dumped : new boolean[] {false, true})
                try (var f = new Fixture(debug, dumped, source)) {
                    f.admit("bad_cv"); var clazz = f.env.fetchClass("ExecutionBadChild"); var dtor = clazz.findMethod("__destruct");
                    f.admit(dtor, dtor.getNativeMethod(), clazz.getData());
                    var objects = f.session.objects(); var provider = objects.registerPropertiesProvider(clazz, clazz.getNativeClass().getConstructor(Environment.class, ClassEntity.class), (e,o) -> {});
                    var allocation = objects.allocate(provider, f.env, new IdentityHashMap<>()); var node = allocation.binding().node();
                    try (var roots = f.session.newCallRoots()) {
                        roots.send(allocation.binding()); allocation.close();
                        try { roots.invokeFunction(f.function("bad_cv"), TraceInfo.UNKNOWN); fail("The return type check must fail"); }
                        catch (php.runtime.lang.exception.BaseTypeError failure) {
                            assertNotNull("Actual PHP failure custody", roots.failureBinding(failure)); assertNull("Failed return has no caller result", roots.result());
                            assertEquals(replaced ? PhpOwnership.State.DISPOSED : PhpOwnership.State.LIVE, node.state());
                            assertEquals(replaced ? "BD" : "B", f.text());
                        }
                    }
                    assertEquals(PhpOwnership.State.DISPOSED, node.state()); assertEquals("BD", f.text());
                }
        }
    }

    @Test public void unknownCalleeAndUnconsumedEvaluationRejectBeforeCallerEffects() throws Throwable {
        String source = "function call_opaque($v){return $v;} function call_before($v){echo 'B';return call_opaque($v);} function call_operand($v){return call_opaque($v)+1;} return 0;";
        for (boolean debug : new boolean[] {false, true}) for (boolean dumped : new boolean[] {false, true})
            try (var f = new Fixture(debug, dumped, source)) {
                var before = f.function("call_before"); var plan = f.plan(before.getData(), before.getNativeMethod());
                assertTrue(plan.required().contains("function-call:call_opaque:1"));
                assertThrows(IllegalArgumentException.class, () -> f.session.registerGenerated(before,before.getNativeMethod(),before.getData(),plan.runtimePlan(),(p,m,b) -> {}));
                assertEquals("", f.text());
                var operand = f.function("call_operand"); var incomplete = f.plan(operand.getData(), operand.getNativeMethod());
                assertTrue(incomplete.unsupported().stream().anyMatch(x -> x.startsWith("unlowered-eval-consumer:")));
                assertThrows(IllegalArgumentException.class, () -> f.session.registerGenerated(operand,operand.getNativeMethod(),operand.getData(),incomplete.runtimePlan(),(p,m,b) -> fail("Incomplete EVAL consumer reached protocol")));
                assertEquals("", f.text());
            }
    }

    @Test public void unsupportedBodiesAndOldAbiNineHaveNoAutomaticAdmission() throws Throwable {
        for (boolean debug : new boolean[] {false, true}) try (var f = new Fixture(debug, false, "function not_lowered(){echo 123; return new stdClass;} return 0;")) {
            var function = f.function("not_lowered"); var plan = f.plan(function.getData(), function.getNativeMethod());
            assertFalse(plan.unsupported().isEmpty()); assertThrows(IllegalArgumentException.class,
                    () -> f.session.registerGenerated(function, function.getNativeMethod(), function.getData(), plan.runtimePlan(), (p,m,b) -> fail("No preflight for an incomplete body")));
            assertEquals("", f.text());
        }
        Environment env = new Environment(newScope(), new ByteArrayOutputStream());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/compiler_provenance_abi9.phb")) {
            assertNotNull(input); ModuleEntity module = new ModuleDumper(new Context("return 0;"), env, true).load(input);
            assertEquals(9, module.getCompilerProvenance().getCompilerAbiVersion()); env.scope.loadModule(module);
            for (var function : module.getFunctions()) assertNull(OwnershipFrameCompiler.read(function.getData(), function.getNativeMethod().getName(), PhpOwnershipExecution.descriptor(function.getNativeMethod())));
        } finally {env.doFinal();}
    }

    @Test public void frameMetadataRejectsVersionCountsDuplicateRootsAndTrailingBytes() throws Throwable {
        try (var f = new Fixture(false, false, "function metadata_cv($v){return $v;} return 0;")) {
            var function = f.function("metadata_cv"); final byte[][] payload = {null};
            new ClassReader(function.getData()).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {@Override public void visitAttribute(org.objectweb.asm.Attribute attr){if(attr instanceof OwnershipFrameCompiler.FrameAttribute data)payload[0]=data.payload();}};
                }
            }, new org.objectweb.asm.Attribute[]{new OwnershipFrameCompiler.FrameAttribute(new byte[0])}, ClassReader.SKIP_CODE);
            assertNotNull(payload[0]); assertNotNull(OwnershipFrameCompiler.decode(payload[0]));
            byte[] badVersion = payload[0].clone(); badVersion[3] = 3; assertThrows(IOException.class, () -> OwnershipFrameCompiler.decode(badVersion));
            var input = new DataInputStream(new ByteArrayInputStream(payload[0])); input.readInt(); for(int i=0;i<4;i++)input.readUTF();
            int countOffset = payload[0].length - input.available(); byte[] badCount = payload[0].clone();
            java.nio.ByteBuffer.wrap(badCount).putInt(countOffset, -1); assertThrows(IOException.class, () -> OwnershipFrameCompiler.decode(badCount));
            byte[] duplicateRoot = payload[0].clone(); java.nio.ByteBuffer.wrap(duplicateRoot).putInt(duplicateRoot.length - 20, OwnershipFrameCompiler.decode(payload[0]).locals().getFirst().id());
            assertThrows(IOException.class, () -> OwnershipFrameCompiler.decode(duplicateRoot));
            byte[] trailing = Arrays.copyOf(payload[0], payload[0].length + 1); assertThrows(IOException.class, () -> OwnershipFrameCompiler.decode(trailing));
            for (int length : new int[]{0,3,payload[0].length-1}) {byte[] truncated = Arrays.copyOf(payload[0], length); assertThrows(IOException.class, () -> OwnershipFrameCompiler.decode(truncated));}
        }
    }
}
