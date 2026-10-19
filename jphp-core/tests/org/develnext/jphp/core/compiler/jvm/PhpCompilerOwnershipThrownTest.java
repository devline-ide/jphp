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
public class PhpCompilerOwnershipThrownTest extends JvmCompilerCase {
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
            var description = plan(bytes, member); assertNotNull(description); assertEquals(2, description.schema()); assertEquals(List.of(), description.unsupported());
            StringWriter diagnostics = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(bytes), env.scope.getClassLoader(), false, new PrintWriter(diagnostics));
            assertEquals("ASM", "", diagnostics.toString());
            session.registerGenerated(entity, member, bytes, description.runtimePlan(), (actual, method, defined) -> {
                var reparsed = plan(defined, method); assertEquals(description, reparsed);
                assertTrue(actual.requiredCapabilities().stream().allMatch(c -> Set.of("frame-local-publication-v1", "empty-tick-handler", "literal-buffered-output", "php-throwable-custody-v1").contains(c) || c.startsWith("function-call:")));
            });
        }
        FunctionEntity function(String name) { return env.fetchFunction(name); }
        void admit(String name) throws Throwable { var f = function(name); admit(f, f.getNativeMethod(), f.getData()); }
        String text() throws Throwable { env.getDefaultBuffer().flush(); return output.toString(StandardCharsets.UTF_8); }
        public void close() throws Exception { scope.close(); participant.close(); try {env.doFinal();} catch (Exception | Error e) {throw e;} catch (Throwable e) {throw new AssertionError(e);} }
    }


    private static final String PREFIX = "class CustodyChild {function __destruct(){echo 'D';}} function custody_bad($a):int{return $a;} ";
    private interface Vector { void run(Fixture fixture) throws Throwable; }
    private void modes(String body, Vector vector) throws Throwable {
        for (boolean strict : new boolean[]{false,true}) for (boolean debug : new boolean[]{false,true}) for(boolean dumped : new boolean[]{false,true})
            try(var f = new Fixture(debug,dumped,(strict?"declare(strict_types=1);":"")+PREFIX+body+" return 0;")) {
                f.admit("custody_bad"); f.admit("custody_main"); vector.run(f);
            }
    }
    private PhpOwnershipObjects.Allocation child(Fixture f) throws Throwable {
        var clazz=f.env.fetchClass("CustodyChild");var dtor=clazz.findMethod("__destruct"); f.admit(dtor,dtor.getNativeMethod(),clazz.getData());
        var provider=f.session.objects().registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,o)->{});
        return f.session.objects().allocate(provider,f.env,new IdentityHashMap<>());
    }
    private void scalar(String body, long expected, String output) throws Throwable {
        modes(body,f->{var allocation=child(f);var node=allocation.binding().node();try(var roots=f.session.newCallRoots()){
            roots.send(allocation.binding());allocation.close();assertEquals(expected,roots.invokeFunction(f.function("custody_main"),TraceInfo.UNKNOWN).toLong());
            assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(output,f.text());
        }});
    }
    private void failure(String body,String output,boolean previous) throws Throwable {
        modes(body,f->{var allocation=child(f);var node=allocation.binding().node();try(var roots=f.session.newCallRoots()){
            roots.send(allocation.binding());allocation.close();try{roots.invokeFunction(f.function("custody_main"),TraceInfo.UNKNOWN);fail("Expected TypeError");}
            catch(php.runtime.lang.exception.BaseTypeError error){assertNotNull(roots.failureBinding(error));assertNull(roots.result());assertEquals(PhpOwnership.State.LIVE,node.state());assertEquals(output,f.text());
                assertEquals(previous,!error.getPrevious(f.env).isNull());}
        }assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(output+"D",f.text());});
    }
    @Test public void pendingReturnRemainsAliveThroughFinallyAndCallerResult() throws Throwable {
        modes("function custody_main($a){try{return $a;}finally{echo 'F';$a=null;}}",f->{var allocation=child(f);var node=allocation.binding().node();var object=allocation.object();try(var roots=f.session.newCallRoots()){
            roots.send(allocation.binding());allocation.close();Memory result=roots.invokeFunction(f.function("custody_main"),TraceInfo.UNKNOWN);assertSame(object,result.toValue(php.runtime.memory.ObjectMemory.class).value);
            assertEquals(PhpOwnership.State.LIVE,node.state());assertEquals(1,node.incoming());assertEquals("F",f.text());
        }assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals("FD",f.text());});
    }
    @Test public void pendingThrownRetainsTraceArgumentsThroughFinally() throws Throwable {
        failure("function custody_main($a){try{custody_bad($a);}finally{echo 'F';$a=null;}}","F",false);
    }
    @Test public void returnFromExceptionalFinallySuppressesOnlyExitedThrowable() throws Throwable {
        scalar("function custody_main($a){try{custody_bad($a);}finally{echo 'F';$a=null;return 7;}}",7,"FD");
    }
    @Test public void catchPublishesActualCvBeforeReleasingCustodyAndReleasesTraceInBody() throws Throwable {
        scalar("function custody_main($a){try{custody_bad($a);}catch(TypeError $e){echo 'C';$a=null;$e=null;echo 'A';}echo 'E';return 8;}",8,"CDAE");
    }
    @Test public void nestedThrownCustodiesPreserveOuterWhileInnerCatchReleases() throws Throwable {
        failure("function custody_main($a){try{custody_bad($a);}finally{echo 'F';try{custody_bad($a);}catch(TypeError $inner){echo 'I';$inner=null;}echo 'A';$a=null;}}","FIA",false);
    }
    @Test public void finallyReplacementHasCertifiedPreviousAndPreservesTraceChild() throws Throwable {
        failure("function custody_main($a){try{custody_bad($a);}finally{echo 'F';custody_bad($a);}}","F",true);
    }

    @Test public void explicitRethrowKeepsExactThrowableIdentityAcrossNestedHandlers() throws Throwable {
        modes("function custody_main($a){try{custody_bad($a);}catch(TypeError $e){$a=null;return $e;}} function custody_rethrow(TypeError $e){try{throw $e;}finally{try{throw $e;}catch(TypeError $inner){$inner=null;}echo 'F';}}",f->{
            f.admit("custody_rethrow");var allocation=child(f);var node=allocation.binding().node();
            try(var first=f.session.newCallRoots()) {
                first.send(allocation.binding());allocation.close();var result=first.invokeFunction(f.function("custody_main"),TraceInfo.UNKNOWN);
                var original=result.toValue(php.runtime.memory.ObjectMemory.class).value;
                try(var second=f.session.newCallRoots()) {
                    second.send(first.result());try{second.invokeFunction(f.function("custody_rethrow"),TraceInfo.UNKNOWN);fail("Expected same Throwable");}
                    catch(php.runtime.lang.exception.BaseTypeError rethrown){assertSame(original,rethrown);assertNotNull(second.failureBinding(rethrown));assertEquals(PhpOwnership.State.LIVE,node.state());assertEquals("F",f.text());}
                }
                assertEquals(PhpOwnership.State.LIVE,node.state());
            }
            assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals("FD",f.text());
        });
    }
    @Test public void successfulFinallyReturnOverrideReleasesOldPendingObject() throws Throwable {
        scalar("function custody_main($a){try{return $a;}finally{echo 'F';$a=null;return 7;}}",7,"FD");
    }
    @Test public void unmatchedCatchDoesNotOverwriteExistingCv() throws Throwable {
        modes("function custody_main($a){$e='old';try{custody_bad($a);}catch(Exception $e){}catch(TypeError $caught){$a=null;$caught=null;}return $e;}",f->{var allocation=child(f);var node=allocation.binding().node();try(var roots=f.session.newCallRoots()){
            roots.send(allocation.binding());allocation.close();assertEquals("old",roots.invokeFunction(f.function("custody_main"),TraceInfo.UNKNOWN).toString());assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals("D",f.text());
        }});
    }
    @Test public void abortedInnerReturnRestoresOuterRawValueAndOwnershipRoot() throws Throwable {
        modes("function custody_main($a){try{return $a;}finally{try{try{return 2;}finally{custody_bad($a);}}catch(TypeError $e){$e=null;}$a=null;}}",f->{var allocation=child(f);var node=allocation.binding().node();var object=allocation.object();try(var roots=f.session.newCallRoots()){
            roots.send(allocation.binding());allocation.close();assertSame(object,roots.invokeFunction(f.function("custody_main"),TraceInfo.UNKNOWN).toValue(php.runtime.memory.ObjectMemory.class).value);assertEquals(PhpOwnership.State.LIVE,node.state());assertEquals("",f.text());
        }assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals("D",f.text());});
    }
    @Test public void returnTypeCheckPrecedesFinallyAndReplacementChainsOriginalFailure() throws Throwable {
        failure("function custody_main($a):int{try{return $a;}finally{echo 'F';custody_bad($a);}}","F",true);
        scalar("function custody_main($a):int{try{return $a;}finally{echo 'F';$a=null;return 7;}}",7,"FD");
    }

    @Test public void transportedSchemaTwoContainsDistinctCustodyRootsAndReadsPhysicalSchemaOne() throws Throwable {
        try(var f=new Fixture(false,true,PREFIX+"function custody_main($a){try{return $a;}finally{try{custody_bad($a);}catch(TypeError $e){$e=null;}}} return 0;")) {
            var function=f.function("custody_main");var plan=f.plan(function.getData(),function.getNativeMethod());
            assertEquals(2,plan.schema());assertEquals(-1,plan.thrown());assertFalse(plan.thrownIds().isEmpty());assertFalse(plan.returnIds().isEmpty());
            var runtime=PhpOwnershipExecution.readFramePlan(function.getData(),function.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(function.getNativeMethod()));
            assertArrayEquals(plan.thrownIds().stream().mapToInt(Integer::intValue).toArray(),runtime.thrownIds());
            assertArrayEquals(plan.returnIds().stream().mapToInt(Integer::intValue).toArray(),runtime.returnIds());
            Set<Integer> roots=new HashSet<>();for(var local:plan.locals())assertTrue(roots.add(local.id()));assertTrue(roots.add(plan.pendingReturn()));
            for(var list:List.of(plan.eval(),plan.thrownIds(),plan.returnIds()))for(int id:list)assertTrue(roots.add(id));
            var payload=new ByteArrayOutputStream();var data=new DataOutputStream(payload);
            data.writeInt(2);for(String field:List.of("Owner","method","()Lphp/runtime/Memory;","FUNCTION"))data.writeUTF(field);
            data.writeInt(0);data.writeInt(0);data.writeInt(0);data.writeInt(100);data.writeInt(-1);data.writeInt(0);
            data.writeInt(1);data.writeInt(200);data.writeInt(1);data.writeInt(300);data.flush();
            byte[] valid=payload.toByteArray();assertEquals(List.of(200),OwnershipFrameCompiler.decode(valid).thrownIds());
            byte[] oldThrown=valid.clone();java.nio.ByteBuffer.wrap(oldThrown).putInt(oldThrown.length-24,400);assertThrows(IOException.class,()->OwnershipFrameCompiler.decode(oldThrown));
            byte[] duplicate=valid.clone();java.nio.ByteBuffer.wrap(duplicate).putInt(duplicate.length-4,200);assertThrows(IOException.class,()->OwnershipFrameCompiler.decode(duplicate));
            byte[] badCount=valid.clone();java.nio.ByteBuffer.wrap(badCount).putInt(badCount.length-16,-1);assertThrows(IOException.class,()->OwnershipFrameCompiler.decode(badCount));
            byte[] trailing=Arrays.copyOf(valid,valid.length+1);assertThrows(IOException.class,()->OwnershipFrameCompiler.decode(trailing));
        }
        Environment env=new Environment(newScope(),new ByteArrayOutputStream());
        try(InputStream input=getClass().getClassLoader().getResourceAsStream("resources/compiler_ownership_schema1_abi10.phb")){
            assertNotNull(input);var module=new ModuleDumper(new Context("return 0;"),env,true).load(input);assertEquals(10,module.getCompilerProvenance().getCompilerAbiVersion());env.scope.loadModule(module);
            int plans=0;for(var function:module.getFunctions()){
                var plan=OwnershipFrameCompiler.read(function.getData(),function.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(function.getNativeMethod()));
                if(plan!=null){plans++;assertEquals(1,plan.schema());assertEquals(List.of(),plan.thrownIds());assertEquals(List.of(),plan.returnIds());}
            }assertTrue("Physical old schema-one compiler product",plans>0);
        }finally{env.doFinal();}
    }
}
