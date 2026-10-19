package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.LangFunctions;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpAttribute;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Real source/PHB bodies, actual SEND/RETURN/FIELD roots and actual owned PHP warning callbacks. */
public class PhpNoDiscardOwnershipTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();final Environment env;final ModuleEntity module;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        Fixture(boolean debug,boolean dump,String source)throws Throwable {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ModuleEntity compiled=new JvmCompiler(env,context).compile(false);
            if(dump){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,true);codec.save(compiled,bytes);compiled=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=compiled;scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
        }
        String text()throws Throwable {env.getDefaultBuffer().flush();return output.toString(StandardCharsets.UTF_8);}
        public void close()throws Exception{try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }
    private static void exactFrame(PhpOwnershipExecution.FramePlan plan,java.lang.reflect.Method member,byte[] bytes)throws Throwable {
        assertEquals(List.of(),plan.unsupportedLowering());var reparsed=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
        assertNotNull(reparsed);assertEquals(plan.ownerInternalName(),reparsed.ownerInternalName());assertEquals(plan.javaMethod(),reparsed.javaMethod());
        assertArrayEquals(plan.evalIds(),reparsed.evalIds());assertEquals(plan.requiredCapabilities(),reparsed.requiredCapabilities());
    }
    private static void admit(PhpOwnershipExecution.Session session,Object entity,java.lang.reflect.Method member,byte[] bytes)throws Throwable {
        var plan=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));assertNotNull(plan);
        session.registerGenerated(entity,member,bytes,plan,PhpNoDiscardOwnershipTest::exactFrame);
    }
    private static void arguments(PhpOwnershipExecution.Session session,ModuleEntity module,FunctionEntity function)throws Throwable {
        for(var attribute:function.getPhpAttributes())for(var argument:attribute.arguments())session.registerAttributeArgument(module,function,attribute,argument,PhpNoDiscardOwnershipTest::exactFrame);
    }
    @Test public void explicitSessionDiscardedFrameUsesFreshArgumentAndNativeReadonlyFieldWithoutLeaks()throws Throwable {
        String source="class NDH {static function handle($n,$s){echo 'W;';return true;}} #[NoDiscard('owned')] function marked($v){echo 'B;';return $v;} function run($v){marked($v);$used=marked($v);(void)marked($v);return $used;}";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,source)) {
            f.module.include(f.env);var participant=f.domain.participant(f.env);var session=new PhpOwnershipExecution.Session(participant,f.env);
            try(var execution=session.enter()) {
                var marked=f.env.fetchFunction("marked");var run=f.env.fetchFunction("run");var handler=f.env.fetchClass("NDH").findMethod("handle");
                admit(session,marked,marked.getNativeMethod(),marked.getData());admit(session,run,run.getNativeMethod(),run.getData());admit(session,handler,handler.getNativeMethod(),handler.getClazz().getData());arguments(session,f.module,marked);
                try(var callable=session.arrays().allocate(false)) {
                    callable.append(session.value(new StringMemory("NDH")));callable.append(session.value(new StringMemory("handle")));
                    try(var roots=session.newCallRoots()){roots.send(callable.binding());roots.invokeFunction(f.env.fetchFunction("set_error_handler"),TraceInfo.UNKNOWN);}
                }
                int live=f.domain.graph().liveNodes();
                for(int i=0;i<2;i++)try(var roots=session.newCallRoots()) {
                    roots.send(session.value(LongMemory.valueOf(7)));assertEquals(7,roots.invokeFunction(run,f.module.getTrace()).toLong());
                    assertNotNull(session.objects().provider(f.env.fetchClass("NoDiscard",false,PhpLanguageProfile.PHP_8_5)));
                }
                assertEquals("native message object/TABLE/FIELD roots retire after each invocation",live,f.domain.graph().liveNodes());
                assertEquals("W;B;B;B;W;B;B;B;",f.text());
                try(var roots=session.newCallRoots()){roots.invokeFunction(f.env.fetchFunction("restore_error_handler"),TraceInfo.UNKNOWN);}
                assertNull(f.env.getErrorHandler());assertNull(PhpOwnershipExecution.currentEntry(f.env));
            }finally{participant.close();}
            assertEquals(0,f.domain.graph().liveNodes());
        }
    }
    private static void exactRuntimeFrame(PhpOwnershipExecution.FramePlan plan,java.lang.reflect.Method member,byte[] bytes)throws Throwable {
        var decoded=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
        assertNotNull(decoded);assertEquals(plan.ownerInternalName(),decoded.ownerInternalName());assertEquals(plan.entryKind(),decoded.entryKind());assertEquals(plan.requiredCapabilities(),decoded.requiredCapabilities());assertArrayEquals(plan.evalIds(),decoded.evalIds());
        if(plan.entryKind()==PhpOwnershipExecution.EntryKind.MODULE || plan.entryKind()==PhpOwnershipExecution.EntryKind.CHUNK){
            assertTrue("explicit module logical-frame restriction retained",plan.unsupportedLowering().contains("module-logical-frame"));
        }else if(plan.unsupportedLowering().isEmpty()){
            exactFrame(plan,member,bytes);
        }else{
            assertTrue(plan.entryKind()==PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE || plan.entryKind()==PhpOwnershipExecution.EntryKind.CLASS_ENV_INITIALIZER);
            assertTrue(php.runtime.lang.Closure.class.isAssignableFrom(member.getDeclaringClass()));
            assertEquals("explicit Session closure imports remain unsupported",List.of("closure-self-and-static-import"),plan.unsupportedLowering());
        }
        var runtime=PhpRuntimeOwnership.readRuntimePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
        assertNotNull(runtime);assertEquals("actual runtime producer/consumer plan",List.of(),runtime.unsupported());
        assertEquals(plan.ownerInternalName(),runtime.owner());assertEquals(member.getName(),runtime.method());assertEquals(PhpOwnershipExecution.descriptor(member),runtime.descriptor());
    }
    private static void nativeHandlers(PhpRuntimeOwnership.Authority authority)throws Throwable {
        for(var member:LangFunctions.class.getDeclaredMethods())if(Set.of("set_error_handler","restore_error_handler").contains(member.getName()))
            authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){if(member.getName().equals("set_error_handler")){assertTrue(arguments.getFirst().raw() instanceof ObjectMemory);assertNotNull(arguments.getFirst().node());}}
            });
    }
    @Test public void runtimeClosureDiscardedInstructionUsesOwnedWarningCallbackAndNonemptyMessage()throws Throwable {
        String source="set_error_handler(function($n,$s){echo 'W;';return true;});$c=#[NoDiscard('closure')] function(){echo 'C;';return 7;};$c();$v=$c();(void)$c();restore_error_handler();return $v;";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,source)) {
            var authority=new PhpRuntimeOwnership.Authority(f.env,f.domain);try(var execution=authority.enter()) {
                nativeHandlers(authority);
                for(var closure:f.module.getClosures()) {
                    var method=closure.findMethod("__invoke");authority.registerMethod(method,closure.getData(),PhpNoDiscardOwnershipTest::exactRuntimeFrame);
                    for(var attribute:method.getPhpAttributes())for(var argument:attribute.arguments())authority.registerAttributeArgument(f.module,method,attribute,argument,PhpNoDiscardOwnershipTest::exactRuntimeFrame);
                }
                authority.registerModule(f.module,f.module.getData(),PhpNoDiscardOwnershipTest::exactRuntimeFrame);
                var plan=PhpRuntimeOwnership.readRuntimePlan(f.module.getData(),f.module.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(f.module.getNativeMethod()));
                assertTrue(plan.calls().stream().anyMatch(PhpRuntimeOwnership.CallSite::discardedResult));
                assertTrue(plan.calls().stream().anyMatch(call->!call.discardedResult()));
                try(var returned=authority.include(f.module)){assertEquals(7,returned.raw().toLong());}
                assertEquals("W;C;C;C;",f.text());assertNull(f.env.getErrorHandler());assertNull(authority.currentFrame());
            }finally{authority.close();}
            assertEquals(0,f.domain.graph().liveNodes());
        }
    }
    @Test public void sourceArgumentAdmissionRejectsForeignOccurrenceBeforeHelperOrConstructor()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"#[NoDiscard('one')] function one(){echo 'BAD';return 1;} #[NoDiscard('two')] function two(){return 2;}")) {
            f.module.include(f.env);var one=f.env.fetchFunction("one");var two=f.env.fetchFunction("two");var attribute=one.getPhpAttributes().getFirst();var argument=attribute.arguments().getFirst();
            var participant=f.domain.participant(f.env);var session=new PhpOwnershipExecution.Session(participant,f.env);
            try(var execution=session.enter()) {
                assertThrows(IOException.class,()->session.registerAttributeArgument(f.module,two,attribute,argument,(p,m,b)->fail("Foreign source reached body certification")));
                var forged=new PhpAttribute(attribute.name(),attribute.trace(),attribute.target(),attribute.strictTypes(),attribute.arguments());
                assertThrows(IOException.class,()->session.registerAttributeArgument(f.module,one,forged,argument,(p,m,b)->fail("Equal metadata is not an occurrence capability")));
                assertEquals("",f.text());assertEquals(0,f.domain.graph().liveNodes());
            }finally{participant.close();}
        }
    }
    @Test public void nativeConstructorPhysicalProofRejectsHeapFieldsAndEffectfulShells()throws Throwable {
        var constructor=php.runtime.ext.core.classes.PhpNoDiscardAttribute.class.getConstructor(Environment.class,ClassEntity.class);
        byte[] original=php.runtime.reflection.helper.PhpNativePropertiesShellVerifier.require(constructor);
        var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(original).accept(node,0);
        node.fields.add(new org.objectweb.asm.tree.FieldNode(org.objectweb.asm.Opcodes.ACC_PUBLIC,"foreign","Lphp/runtime/Memory;",null,null));
        var output=new org.objectweb.asm.ClassWriter(0);node.accept(output);byte[] foreign=output.toByteArray();
        assertThrows(IOException.class,()->php.runtime.reflection.helper.PhpNativePropertiesShellVerifier.require(foreign,constructor.getDeclaringClass().getName()));
        node.fields.removeLast();var body=node.methods.stream().filter(method->method.name.equals("<init>")).findFirst().orElseThrow();
        body.instructions.insert(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,"foreign/Effect","run","()V",false));
        output=new org.objectweb.asm.ClassWriter(0);node.accept(output);byte[] effectful=output.toByteArray();
        assertThrows(IOException.class,()->php.runtime.reflection.helper.PhpNativePropertiesShellVerifier.require(effectful,constructor.getDeclaringClass().getName()));
    }
}
