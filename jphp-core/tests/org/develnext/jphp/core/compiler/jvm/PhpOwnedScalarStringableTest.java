package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Compiled PHP receivers, actual native SEND roots, checked callback RETURN and exact BODY capability negatives. */
public class PhpOwnedScalarStringableTest {
    private static final ThreadLocal<Probe> PROBE=new ThreadLocal<>();
    private static final class Probe {
        PhpRuntimeOwnership.Authority authority;PhpOwnership.Node sent;int hits,alive;
        final php.runtime.exceptions.CriticalException failure=new php.runtime.exceptions.CriticalException("scalar callback failure");
    }
    public static final class ProbeExtension extends php.runtime.ext.support.Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new ProbeFunctions());}
    }
    public static final class ProbeFunctions extends php.runtime.ext.support.compile.FunctionsContainer {
        private static Probe actual(Environment env,String name)throws Exception {
            var probe=PROBE.get();assertNotNull(probe);
            var body=PhpRuntimeOwnership.currentNativeBody(env);assertNotNull("Compiled callback must enter its actual native BODY",body);
            assertEquals(ProbeFunctions.class.getMethod(name,Environment.class),body.member());return probe;
        }
        public static long ord_callback_hit(Environment env)throws Exception {actual(env,"ord_callback_hit").hits++;return 1;}
        public static long ord_callback_alive(Environment env)throws Exception {
            var probe=actual(env,"ord_callback_alive");assertNotNull(probe.sent);
            assertEquals("Original global source is gone but SEND and callback receiver still own it",PhpOwnership.State.LIVE,probe.sent.state());
            assertTrue("Actual ord SEND plus actual method receiver SEND must survive",probe.sent.incoming()>=2);
            probe.alive++;return 1;
        }
        public static long ord_callback_fail(Environment env)throws Exception {throw actual(env,"ord_callback_fail").failure;}
    }
    private interface Check {void run(Fixture f)throws Throwable;}
    private static final class Fixture implements AutoCloseable {
        final CompileScope scope=new CompileScope();final Environment env;final ModuleEntity module;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();final PhpRuntimeOwnership.Authority authority;
        final PhpOwnershipDomain.Execution execution;final Probe probe=new Probe();
        final java.lang.reflect.Method ord=php.runtime.ext.core.StringFunctions.class.getMethod("nativeOrd",Environment.class,TraceInfo.class,Memory.class);
        final java.lang.reflect.Method chr=php.runtime.ext.core.StringFunctions.class.getMethod("nativeChr",Environment.class,TraceInfo.class,Memory.class);
        Fixture(boolean debug,boolean dumped,boolean strict)throws Throwable {
            scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());scope.registerExtension(new ProbeExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
            String source;try(var in=PhpOwnedScalarStringableTest.class.getClassLoader().getResourceAsStream("scalar-stringable/declarations.php.inc")) {
                assertNotNull(in);source="declare(strict_types="+(strict?1:0)+");"+new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var compiled=new JvmCompiler(env,context).compile(false);
            if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(compiled,bytes);compiled=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=compiled;scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            for(var clazz:module.getClasses())clazz.initializeRuntimeState(env);
            authority=new PhpRuntimeOwnership.Authority(env,domain);execution=authority.enter();probe.authority=authority;PROBE.set(probe);
            register(ord,1);register(chr,1);
            for(String name:new String[]{"ord_callback_hit","ord_callback_alive","ord_callback_fail"})register(ProbeFunctions.class.getMethod(name,Environment.class),0);
        }
        private void register(java.lang.reflect.Method exact,int argc) {
            authority.registerNative(exact,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                public void validateMember(java.lang.reflect.Method actual){assertEquals(exact,actual);}
                public void validateArguments(List<PhpRuntimeOwnership.Value> args){
                    assertEquals(argc,args.size());
                    if(exact.equals(ord)&&args.get(0).raw() instanceof ObjectMemory) {
                        var value=args.get(0);assertEquals(PhpRuntimeOwnership.Role.VALUE,value.role());assertNotNull(value.node());probe.sent=value.node();
                    }
                }
            });
        }
        FunctionEntity admit(String name)throws Throwable {
            var fn=env.fetchFunction(name);assertNotNull(fn);defined(fn.getNativeMethod(),fn.getData());
            authority.registerFunction(fn,fn.getData(),(plan,member,bytes)->assertEquals(PhpOwnershipExecution.EntryKind.FUNCTION,plan.entryKind()));return fn;
        }
        void magic(String name)throws Throwable {
            var clazz=env.fetchClass(name);var method=clazz.methodMagicToString;assertNotNull(method);defined(method.getNativeMethod(),clazz.getData());
            authority.registerMethod(method,clazz.getData(),(plan,member,bytes)->assertEquals(PhpOwnershipExecution.EntryKind.METHOD,plan.entryKind()));
        }
        private void defined(java.lang.reflect.Method member,byte[] bytes) {
            assertEquals(69,((bytes[6]&255)<<8)|(bytes[7]&255));
            var loader=(php.runtime.loader.RuntimeClassLoader)member.getDeclaringClass().getClassLoader();
            assertTrue("SOURCE/PHB input is the actual defined class",loader.matchesDefinedBytecode(member.getDeclaringClass(),bytes));
        }
        void output(String name,long expected)throws Throwable {
            try(var returned=authority.invoke(admit(name),TraceInfo.UNKNOWN)){assertEquals(expected,returned.raw().toLong());}
            balanced();
        }
        void balanced(){assertNull(authority.currentFrame());assertNull(PhpRuntimeOwnership.currentNativeBody(env));}
        PhpRuntimeOwnership.NativePacket packet(PhpRuntimeOwnership.Frame frame,int id,java.lang.reflect.Method member,Memory actual)throws Throwable {
            var packet=frame.nativeCall(id,member.getDeclaringClass(),member.getName(),PhpOwnershipExecution.descriptor(member),1);packet.send(0,actual,-1);return packet;
        }
        public void close()throws Exception {
            try{authority.close();}finally{PROBE.remove();execution.close();try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}finally{scope.getClassLoader().close();}}
        }
    }
    private void modes(boolean strict,Check check)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(debug,dumped,strict)){check.run(f);}
    }
    @Test public void actualCompiledRawStringableUsesFirstByteAndClosesSend()throws Throwable {
        modes(false,f->{f.magic("OrdRaw");f.output("owned_raw",255);assertEquals(1,f.probe.hits);assertEquals(PhpOwnership.State.DISPOSED,f.probe.sent.state());});
    }
    @Test public void actualCompiledUnicodeStringableUsesUtf8FirstByte()throws Throwable {
        modes(false,f->{f.magic("OrdUtf");f.output("owned_utf",195);assertEquals(1,f.probe.hits);assertEquals(PhpOwnership.State.DISPOSED,f.probe.sent.state());});
    }
    @Test public void strictModeRejectsBeforeCallbackAndNextCallWorks()throws Throwable {
        modes(true,f->{f.magic("OrdRaw");var fn=f.admit("owned_raw");
            try(var ignored=f.authority.invoke(fn,TraceInfo.UNKNOWN)){fail("Strict Stringable was coerced");}
            catch(php.runtime.lang.exception.BaseTypeError failure){assertEquals("ord(): Argument #1 ($character) must be of type string, OrdRaw given",failure.getMessage(f.env).toString());}
            assertEquals(0,f.probe.hits);f.balanced();f.output("owned_plain",65);assertEquals(0,f.domain.graph().liveNodes());});
    }
    @Test public void actualSendAndCallbackReceiverSurviveSourceGlobalReplacement()throws Throwable {
        modes(false,f->{f.magic("OrdDrop");f.output("owned_drop",255);assertEquals(1,f.probe.alive);assertEquals(PhpOwnership.State.DISPOSED,f.probe.sent.state());
            assertTrue(f.env.getGlobals().getByScalar("character").toValue().isNull());f.output("owned_plain",65);});
    }
    @Test public void wrongCallbackReturnCleansPacketAndNextCallWorks()throws Throwable {
        modes(false,f->{f.magic("OrdWrong");var fn=f.admit("owned_wrong");
            try(var ignored=f.authority.invoke(fn,TraceInfo.UNKNOWN)){fail("Array callback return was accepted");}
            catch(php.runtime.lang.exception.BaseTypeError failure){assertEquals("OrdWrong::__toString(): Return value must be of type string, array returned",failure.getMessage(f.env).toString());}
            assertEquals(1,f.probe.hits);f.balanced();f.output("owned_plain",65);assertEquals(0,f.domain.graph().liveNodes());});
    }
    @Test public void throwingCallbackPreservesPrimaryIdentityAndNextCallWorks()throws Throwable {
        modes(false,f->{f.magic("OrdThrow");var fn=f.admit("owned_throw");
            try(var ignored=f.authority.invoke(fn,TraceInfo.UNKNOWN)){fail("Actual callback failure was swallowed");}
            catch(php.runtime.exceptions.CriticalException failure){assertSame(f.probe.failure,failure);}
            f.balanced();f.output("owned_plain",65);assertEquals(0,f.domain.graph().liveNodes());});
    }
    @Test public void scalarBodyRejectsAnotherArgumentIndexBeforeCallback()throws Throwable {
        modes(false,f->{f.magic("OrdRaw");try(var result=f.authority.invoke(f.admit("owned_object"),TraceInfo.UNKNOWN);var frame=f.authority.frame();var packet=f.packet(frame,4_009_001,f.ord,result.raw())) {
            var body=PhpRuntimeOwnership.nativeEnterBody(packet);assertThrows(IllegalArgumentException.class,()->body.scalarStringable(1,result.raw().toValue(ObjectMemory.class),TraceInfo.UNKNOWN));
            assertEquals(0,f.probe.hits);assertSame(body,PhpRuntimeOwnership.currentNativeBody(f.env));PhpRuntimeOwnership.nativeLeaveBody(body);
        }f.balanced();f.output("owned_plain",65);});
    }
    @Test public void unannotatedNativeMemberCannotUseScalarCallbackCapability()throws Throwable {
        modes(false,f->{f.magic("OrdRaw");try(var result=f.authority.invoke(f.admit("owned_object"),TraceInfo.UNKNOWN);var frame=f.authority.frame();var packet=f.packet(frame,4_009_002,f.chr,result.raw())) {
            var body=PhpRuntimeOwnership.nativeEnterBody(packet);assertThrows(IllegalArgumentException.class,()->body.scalarStringable(0,result.raw().toValue(ObjectMemory.class),TraceInfo.UNKNOWN));
            assertEquals(0,f.probe.hits);PhpRuntimeOwnership.nativeLeaveBody(body);
        }f.balanced();f.output("owned_plain",65);});
    }
    @Test public void scalarBodyRejectsDifferentCertifiedReceiverButAcceptsSameObjectWrapper()throws Throwable {
        modes(false,f->{f.magic("OrdRaw");var fn=f.admit("owned_object");try(var first=f.authority.invoke(fn,TraceInfo.UNKNOWN);var second=f.authority.invoke(fn,TraceInfo.UNKNOWN);var frame=f.authority.frame();var packet=f.packet(frame,4_009_003,f.ord,first.raw())) {
            var body=PhpRuntimeOwnership.nativeEnterBody(packet);
            assertThrows(IllegalArgumentException.class,()->body.scalarStringable(0,second.raw().toValue(ObjectMemory.class),TraceInfo.UNKNOWN));assertEquals(0,f.probe.hits);
            var same=new ObjectMemory(first.raw().toValue(ObjectMemory.class).value);
            assertEquals(255,body.scalarStringable(0,same,TraceInfo.UNKNOWN).getBinaryBytes(StandardCharsets.UTF_8)[0]&255);assertEquals(1,f.probe.hits);
            PhpRuntimeOwnership.nativeLeaveBody(body);assertThrows(IllegalStateException.class,()->body.scalarStringable(0,same,TraceInfo.UNKNOWN));
        }f.balanced();f.output("owned_plain",65);});
    }
    @Test public void scalarCapabilityCannotUseJoinCallbackGuard()throws Throwable {
        modes(false,f->{f.magic("OrdRaw");try(var result=f.authority.invoke(f.admit("owned_object"),TraceInfo.UNKNOWN);var frame=f.authority.frame();var packet=f.packet(frame,4_009_004,f.ord,result.raw())) {
            var body=PhpRuntimeOwnership.nativeEnterBody(packet);assertThrows(IllegalArgumentException.class,()->body.stringable(result.raw().toValue(ObjectMemory.class),TraceInfo.UNKNOWN));
            assertEquals(0,f.probe.hits);PhpRuntimeOwnership.nativeLeaveBody(body);
        }f.balanced();f.output("owned_plain",65);});
    }

    private static org.objectweb.asm.tree.AbstractInsnNode previousOpcode(org.objectweb.asm.tree.AbstractInsnNode node) {
        do {node=node.getPrevious();} while(node!=null&&node.getOpcode()<0);return node;
    }
    private static byte[] changedLongResult(byte[] original,String methodName,int kind) {
        var reader=new org.objectweb.asm.ClassReader(original);var tree=new org.objectweb.asm.tree.ClassNode();reader.accept(tree,0);
        boolean changed=false;
        for(var method:tree.methods)if(method.name.equals(methodName))for(var node:method.instructions.toArray())
            if(node instanceof org.objectweb.asm.tree.MethodInsnNode call&&call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("nativeResult")) {
                var id=previousOpcode(node);var raw=previousOpcode(id);var packet=previousOpcode(raw);var store=previousOpcode(packet);var box=previousOpcode(store);
                assertTrue(box instanceof org.objectweb.asm.tree.MethodInsnNode);var boxing=(org.objectweb.asm.tree.MethodInsnNode)box;
                assertEquals("php/runtime/memory/LongMemory",boxing.owner);assertEquals("valueOf",boxing.name);assertEquals("(J)Lphp/runtime/Memory;",boxing.desc);
                if(kind==0)boxing.desc="(I)Lphp/runtime/Memory;";
                else if(kind==1) {assertTrue(raw instanceof org.objectweb.asm.tree.VarInsnNode);assertEquals(org.objectweb.asm.Opcodes.ALOAD,raw.getOpcode());((org.objectweb.asm.tree.VarInsnNode)raw).var++;}
                else {assertTrue(id instanceof org.objectweb.asm.tree.LdcInsnNode);assertTrue(((org.objectweb.asm.tree.LdcInsnNode)id).cst instanceof Integer);((org.objectweb.asm.tree.LdcInsnNode)id).cst=(Integer)((org.objectweb.asm.tree.LdcInsnNode)id).cst+1;}
                changed=true;break;
            }
        assertTrue("Actual selected native long RESULT boxing was absent",changed);
        var writer=new org.objectweb.asm.ClassWriter(reader,0);tree.accept(writer);return writer.toByteArray();
    }
    private void rejectsLongResult(int kind)throws Throwable {
        modes(false,f->{var fn=f.env.fetchFunction("owned_plain");var method=fn.getNativeMethod();
            var actual=PhpRuntimeOwnership.readRuntimePlan(fn.getData(),method.getName(),PhpOwnershipExecution.descriptor(method));
            assertEquals("Unmodified actual long BODY must be admitted before testing mutations",Collections.emptyList(),actual.unsupported());
            // Check a lossless no-op ASM round trip separately so unknown ownership attributes cannot explain rejection.
            var reader=new org.objectweb.asm.ClassReader(fn.getData());var writer=new org.objectweb.asm.ClassWriter(reader,0);reader.accept(writer,0);
            var roundTrip=PhpRuntimeOwnership.readRuntimePlan(writer.toByteArray(),method.getName(),PhpOwnershipExecution.descriptor(method));assertEquals(Collections.emptyList(),roundTrip.unsupported());
            byte[] altered=changedLongResult(fn.getData(),method.getName(),kind);
            boolean rejected=false;try {PhpRuntimeOwnership.readRuntimePlan(altered,method.getName(),PhpOwnershipExecution.descriptor(method));}catch(IOException expected){rejected=true;}
            assertTrue("Altered selected long BODY RESULT was admitted: "+kind,rejected);
            f.output("owned_plain",65);assertEquals(0,f.domain.graph().liveNodes());
        });
    }
    @Test public void longNativeResultCannotUseIntBoxingDescriptor()throws Throwable {rejectsLongResult(0);}
    @Test public void longNativeResultCannotReadAnotherRawLocal()throws Throwable {rejectsLongResult(1);}
    @Test public void longNativeResultCannotUseAnotherEvaluationId()throws Throwable {rejectsLongResult(2);}
}
