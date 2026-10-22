package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.ext.core.LangFunctions;
import php.runtime.lang.Closure;
import php.runtime.lang.support.IPhpOwnershipObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.helper.ClosureEntity;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual Authority coverage for the ABI26 explicit VALUE warning producer. */
public final class PhpActiveCaptureWarningTest {
    private static final String SOURCE="$prior=[];$v=7;set_error_handler(function($n,$s)use($v){$GLOBALS['activeMissing']=$v;active_capture_warning_probe($n,$s);return true;});active_capture_warning_installed();return function()use($prior,$activeMissing){return $activeMissing;};";
    private static final ThreadLocal<State> CURRENT=new ThreadLocal<>();
    private static final class State {
        final Environment env;
        final PhpRuntimeOwnership.Authority authority;
        final int captureProducer;
        final boolean throwing;
        final php.runtime.exceptions.CriticalException sentinel=new php.runtime.exceptions.CriticalException("active-capture-warning-host-sentinel");
        PhpRuntimeOwnership.Frame moduleFrame;
        PhpOwnership.Node pendingNode,handlerNode,priorNode;
        Object priorStorage;
        Closure registeredHandler;
        php.runtime.env.handler.ErrorHandler selectedHandler;
        PhpOwnershipNativeCalls.Registration selectedRegistration;
        int installed;
        int registrations,warnings;
        State(Environment env,PhpRuntimeOwnership.Authority authority,int producer,boolean throwing){this.env=env;this.authority=authority;captureProducer=producer;this.throwing=throwing;}
    }
    public static final class ProbeExtension extends php.runtime.ext.support.Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new ProbeFunctions());}
    }
    public static final class ProbeFunctions extends php.runtime.ext.support.compile.FunctionsContainer {
        public static Memory active_capture_warning_installed(Environment env) {
            State state=Objects.requireNonNull(CURRENT.get());assertSame(state.env,env);assertSame(state.authority,PhpRuntimeOwnership.current(env));
            assertSame(state.moduleFrame,state.authority.currentFrame());assertNull(state.selectedHandler);
            var handler=env.getErrorHandler();assertNotNull(handler);assertNotNull(handler.getOwnershipRegistration());
            assertTrue(handler.getOwnershipRegistration().belongsTo(state.authority));
            assertSame(state.registeredHandler,((ObjectMemory)handler.invokerMemory).value);
            assertSame(state.handlerNode,handler.getOwnershipRegistration().binding().node());
            state.selectedHandler=handler;state.selectedRegistration=handler.getOwnershipRegistration();assertEquals(1,++state.installed);
            return Memory.TRUE;
        }
        public static Memory active_capture_warning_probe(Environment env,Memory severity,Memory message) {
            State state=Objects.requireNonNull(CURRENT.get());
            assertSame(state.env,env);assertSame(state.authority,PhpRuntimeOwnership.current(env));
            assertNotNull(state.moduleFrame);assertNotNull(state.authority.currentFrame());
            assertNotSame("Warning actually reenters a registered lexical callback frame",state.moduleFrame,state.authority.currentFrame());
            assertEquals(2,severity.toLong());assertEquals("Undefined variable $activeMissing",message.toString());
            assertEquals("Handler creates the named slot before capture resumes",7,env.getRuntimeGlobalsTable().getByScalar("activeMissing").toLong());
            assertNull("The actual selected handler is suspended during its callback",env.getErrorHandler());
            assertNotNull(state.selectedHandler);assertSame(state.selectedRegistration,state.selectedHandler.getOwnershipRegistration());
            assertTrue(state.selectedRegistration.belongsTo(state.authority));assertEquals(PhpOwnership.State.LIVE,state.handlerNode.state());
            var evaluation=state.moduleFrame.evalHolder(state.captureProducer);assertNotNull("Actual enclosing closure EVAL is already staged",evaluation);
            assertEquals(PhpRuntimeOwnership.Role.VALUE,evaluation.role());
            var pending=(ObjectMemory)evaluation.raw();var closure=(Closure)pending.value;
            var object=((IPhpOwnershipObject)closure).getOwnershipObject();assertNotNull(object);
            assertNull("Capture warning precedes completed descriptor publication",object.closure());
            state.pendingNode=object.node();assertEquals(PhpOwnership.State.LIVE,state.pendingNode.state());
            assertNotNull(state.priorNode);assertEquals("Earlier actual ARRAY VALUE is pinned before warning reentry",2,state.priorNode.incoming());
            assertEquals("One warning per creation",1,++state.warnings);
            if(state.throwing)throw state.sentinel;
            return Memory.TRUE;
        }
    }
    @BeforeClass public static void actualOrigins()throws Exception {
        String runtime=System.getProperty("qa.runtime"),core=System.getProperty("qa.core");
        if(runtime!=null)assertEquals(Path.of(runtime).toRealPath(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        if(core!=null)assertEquals(Path.of(core).toRealPath(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(28,ModuleDumper.COMPILER_ABI_VERSION);
        try(var stdout=PhpActiveCaptureWarningTest.class.getClassLoader().getResourceAsStream("resources/native-oracle/active-capture-warning/native.stdout");
            var stderr=PhpActiveCaptureWarningTest.class.getClassLoader().getResourceAsStream("resources/native-oracle/active-capture-warning/native.stderr")) {
            assertNotNull("Pinned native capture/earlier ARRAY oracle",stdout);assertNotNull(stderr);
            assertEquals("[null,7,[],[[2,\"Undefined variable $activeMissing\"]]]\n",new String(stdout.readAllBytes(),StandardCharsets.UTF_8));assertEquals(0,stderr.readAllBytes().length);
        }
    }
    private static void verifyActiveInstruction(byte[] bytes) {
        int[] calls={0};new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9){
                @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf){
                    if(opcode==Opcodes.INVOKEVIRTUAL&&owner.equals("php/runtime/PhpOwnershipClosures$RuntimeAllocation")&&name.equals("capture")&&descriptor.equals("(ILphp/runtime/Memory;[Lphp/runtime/env/TraceInfo;I)V"))calls[0]++;
                }
            };}
        },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        assertTrue("Actual emitted typed explicit VALUE overload",calls[0]>0);
    }
    private static boolean containsIdentity(Throwable actual,Throwable expected) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());var pending=new ArrayDeque<Throwable>();
        if(actual!=null)pending.add(actual);
        while(!pending.isEmpty()) {
            Throwable current=pending.removeFirst();if(!seen.add(current))continue;if(current==expected)return true;
            if(current instanceof php.runtime.ext.java.JavaException wrapped&&wrapped.getThrowable()!=null)pending.add(wrapped.getThrowable());
            if(current.getCause()!=null)pending.add(current.getCause());for(Throwable suppressed:current.getSuppressed())pending.add(suppressed);
        }
        return false;
    }
    private static void runCase(boolean throwing,boolean debug,boolean dumped)throws Throwable {
        var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.registerExtension(new ProbeExtension());
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
        try {
            var context=new Context(SOURCE);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(dumped){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);verifyActiveInstruction(module.getData());
            ClosureEntity captured=module.getClosures().stream().filter(c->c.getOwnershipCapturePlan().entries().stream().anyMatch(e->e.name().equals("activeMissing"))).findFirst().orElseThrow();
            assertEquals(2,captured.getOwnershipCapturePlan().entries().size());
            assertEquals("prior",captured.getOwnershipCapturePlan().entries().getFirst().name());
            for(var entry:captured.getOwnershipCapturePlan().entries())assertEquals(ClosureEntity.CaptureMode.VALUE,entry.mode());
            var method=module.getNativeMethod();var runtimePlan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),method.getName(),PhpOwnershipExecution.descriptor(method));assertNotNull(runtimePlan);
            var literal=runtimePlan.literals().stream().filter(l->l.closure().equals(captured.getInternalName())).findFirst().orElseThrow();
            var authority=new PhpRuntimeOwnership.Authority(env,domain);var state=new State(env,authority,literal.producer(),throwing);CURRENT.set(state);
            Throwable primary=null;
            try(var execution=authority.enter();var owned=authority) {
              try {
                var install=LangFunctions.class.getMethod("set_error_handler",Environment.class,TraceInfo.class,Memory.class);
                authority.registerNative(install,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                    public void validateMember(java.lang.reflect.Method actual){assertEquals(install,actual);assertEquals(LangFunctions.class,actual.getDeclaringClass());}
                    public void validateArguments(List<PhpRuntimeOwnership.Value> values){
                        assertEquals(1,values.size());var value=values.getFirst();assertSame(authority,value.authority());assertEquals(PhpRuntimeOwnership.Role.VALUE,value.role());
                        assertTrue(value.raw() instanceof ObjectMemory);var handler=(Closure)((ObjectMemory)value.raw()).value;assertNotNull(handler.ownershipClosure());
                        assertEquals(1,handler.ownershipClosure().captureBindings().size());assertEquals(PhpOwnershipValues.Role.VALUE,handler.ownershipClosure().captureBindings().getFirst().role());
                        assertEquals(7,handler.ownershipClosure().captureBindings().getFirst().rawValue().toLong());
                        state.registeredHandler=handler;state.handlerNode=handler.ownershipClosure().objectCertificate().node();assertEquals(PhpOwnership.State.LIVE,state.handlerNode.state());
                        var prior=env.getRuntimeGlobalsTable().getByScalar("prior").toValue(ArrayMemory.class);assertNotNull(prior.getRuntimeOrigin());
                        state.priorNode=prior.getRuntimeOrigin().node();state.priorStorage=prior.getStorageIdentity();
                        assertEquals("Only the actual named ARRAY slot exists before target capture",1,state.priorNode.incoming());
                        state.moduleFrame=authority.currentFrame();assertNotNull(state.moduleFrame);assertEquals(1,++state.registrations);
                    }
                });
                var installed=ProbeFunctions.class.getMethod("active_capture_warning_installed",Environment.class);
                authority.registerNative(installed,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                    public void validateMember(java.lang.reflect.Method actual){assertEquals(installed,actual);assertEquals(ProbeFunctions.class,actual.getDeclaringClass());}
                    public void validateArguments(List<PhpRuntimeOwnership.Value> values){assertEquals(0,values.size());assertSame(authority,PhpRuntimeOwnership.current(env));assertSame(state.moduleFrame,authority.currentFrame());assertEquals(1,state.registrations);}
                });
                var probe=ProbeFunctions.class.getMethod("active_capture_warning_probe",Environment.class,Memory.class,Memory.class);
                authority.registerNative(probe,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                    public void validateMember(java.lang.reflect.Method actual){assertEquals(probe,actual);assertEquals(ProbeFunctions.class,actual.getDeclaringClass());}
                    public void validateArguments(List<PhpRuntimeOwnership.Value> values){
                        assertEquals(2,values.size());for(var value:values){assertSame(authority,value.authority());assertEquals(PhpRuntimeOwnership.Role.VALUE,value.role());assertNull(value.node());}
                        assertEquals(2,values.getFirst().raw().toLong());assertEquals("Undefined variable $activeMissing",values.get(1).raw().toString());
                    }
                });
                for(var closure:module.getClosures()) {
                    var invoke=closure.findMethod("__invoke");byte[] bytes=closure.getData();
                    authority.registerMethod(invoke,bytes,(plan,member,actual)->{assertEquals(PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE,plan.entryKind());assertEquals(invoke.getNativeMethod(),member);assertArrayEquals(bytes,actual);});
                }
                byte[] moduleBytes=module.getData();var moduleMethod=module.getNativeMethod();
                authority.registerModule(module,moduleBytes,(plan,member,actual)->{assertEquals(PhpOwnershipExecution.EntryKind.MODULE,plan.entryKind());assertEquals(moduleMethod,member);assertArrayEquals(moduleBytes,actual);});
                Throwable escaped=null;
                try(var result=authority.include(module)) {
                    if(throwing)fail("Host sentinel must escape warning callback");
                    var closure=(Closure)((ObjectMemory)result.raw()).value;assertNotNull(closure.ownershipClosure());assertSame(state.pendingNode,closure.ownershipClosure().objectCertificate().node());
                    var bindings=closure.ownershipClosure().captureBindings();assertEquals(2,bindings.size());
                    var priorBinding=bindings.getFirst();assertEquals(PhpOwnershipValues.Role.VALUE,priorBinding.role());assertTrue(priorBinding.rawValue() instanceof ArrayMemory);assertSame(state.priorNode,priorBinding.node());assertSame(state.priorStorage,((ArrayMemory)priorBinding.rawValue()).getStorageIdentity());assertEquals(0,((ArrayMemory)priorBinding.rawValue()).size());
                    var binding=bindings.get(1);assertEquals(PhpOwnershipValues.Role.VALUE,binding.role());assertTrue(binding.rawValue().isNull());assertFalse(binding.rawValue() instanceof ReferenceMemory);assertNull(binding.node());
                    assertTrue(closure.getUses()[0] instanceof ArrayMemory);assertFalse(closure.getUses()[0] instanceof ReferenceMemory);
                    assertTrue(closure.getUses()[1].isNull());assertFalse(closure.getUses()[1] instanceof ReferenceMemory);
                    try(var value=authority.invoke(captured.findMethod("__invoke"),closure,TraceInfo.UNKNOWN)){assertTrue("Captured NULL survives named-global replacement",value.raw().isNull());}
                } catch(Throwable failure){if(!throwing)throw failure;escaped=failure;}
                if(throwing){assertNotNull(escaped);if(!containsIdentity(escaped,state.sentinel))throw new AssertionError("The exact native host sentinel must remain in the known Throwable graph",escaped);}
                assertEquals(1,state.registrations);assertEquals(1,state.installed);assertEquals(1,state.warnings);assertNotNull(state.pendingNode);
                assertEquals("Returned/pending closure retires on both success and unwind",PhpOwnership.State.DISPOSED,state.pendingNode.state());
                assertEquals("Earlier managed capture pin retires on success and warning unwind; only named global remains",1,state.priorNode.incoming());
                assertEquals(7,env.getRuntimeGlobalsTable().getByScalar("activeMissing").toLong());assertEquals(0,output.size());
                assertSame("Exact selected handler restored after success or host throw",state.selectedHandler,env.getErrorHandler());
                assertSame(state.selectedRegistration,env.getErrorHandler().getOwnershipRegistration());assertTrue(state.selectedRegistration.belongsTo(authority));
                assertSame(state.registeredHandler,((ObjectMemory)env.getErrorHandler().invokerMemory).value);assertEquals(PhpOwnership.State.LIVE,state.handlerNode.state());
              } catch(Throwable failure) {primary=failure;throw failure;}
              finally {
                try {
                    env.releaseOwnedErrorHandlers();assertNull(env.getErrorHandler());
                    if(state.handlerNode!=null)assertEquals("Actual callback FIELD is released",PhpOwnership.State.DISPOSED,state.handlerNode.state());
                    if(state.selectedRegistration!=null)assertFalse("Exact saved registration is closed",state.selectedRegistration.belongsTo(authority));
                } catch(Throwable cleanup) {if(primary==null)throw cleanup;if(cleanup!=primary)primary.addSuppressed(cleanup);}
              }
            }
            assertEquals("Earlier managed ARRAY retires with the actual named slot",PhpOwnership.State.DISPOSED,state.priorNode.state());
            assertEquals("No pending capture, callback, or invocation owner after retirement",0,domain.graph().liveNodes());
            System.out.println("CELL active-capture-warning throwing="+throwing+" debug="+debug+" phb="+dumped+" PASS");
        } finally {CURRENT.remove();env.doFinal();}
    }
    @Test public void undefinedValueIsCapturedBeforeHandlerCreatesNamedGlobalSourcePhbDebug()throws Throwable {for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})runCase(false,debug,phb);}
    @Test public void nativeHostThrowUnwindsActualPendingCaptureAndCallbackOwnersSourcePhbDebug()throws Throwable {for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})runCase(true,debug,phb);}
}
