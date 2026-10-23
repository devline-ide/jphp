package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.LangFunctions;
import php.runtime.ext.core.classes.PhpDeprecatedAttribute;
import php.runtime.invoke.PhpDeprecatedOwnedInvocation;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.Entity;
import php.runtime.reflection.support.PhpAttribute;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Executable source/PHB controls. Assertions describe bounded protocol effects, not new native goldens. */
public class PhpDeprecatedOwnershipTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Environment env;final ModuleEntity module;final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        Fixture(boolean debug,boolean dump,String source)throws Throwable {
            var scope=newScope();scope.setDebugMode(debug);
            assertEquals("Modern declaration must not require flipping the host scope",PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
            env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);env.setErrorFlags(-1);
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ModuleEntity compiled=new JvmCompiler(env,context).compile(false);
            if(dump){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,true);
                codec.save(compiled,bytes);compiled=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=compiled;scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
        }
        String text()throws Throwable {env.getDefaultBuffer().flush();return output.toString(StandardCharsets.UTF_8);}
        public void close()throws Exception {
            try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}
            finally{env.getScope().getClassLoader().close();}
        }
    }
    private static void exactFrame(PhpOwnershipExecution.FramePlan plan,java.lang.reflect.Method member,byte[] bytes)throws Throwable {
        assertEquals(List.of(),plan.unsupportedLowering());
        var decoded=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
        assertNotNull(decoded);assertEquals(plan.ownerInternalName(),decoded.ownerInternalName());
        assertEquals(plan.javaMethod(),decoded.javaMethod());assertEquals(plan.descriptor(),decoded.descriptor());
        assertEquals(plan.requiredCapabilities(),decoded.requiredCapabilities());assertArrayEquals(plan.evalIds(),decoded.evalIds());
    }
    private static void exactRuntimeFrame(PhpOwnershipExecution.FramePlan plan,java.lang.reflect.Method member,byte[] bytes)throws Throwable {
        var decoded=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
        assertNotNull(decoded);assertEquals(plan.ownerInternalName(),decoded.ownerInternalName());
        assertEquals(plan.entryKind(),decoded.entryKind());assertEquals(plan.requiredCapabilities(),decoded.requiredCapabilities());
        assertArrayEquals(plan.evalIds(),decoded.evalIds());
        if(plan.entryKind()==PhpOwnershipExecution.EntryKind.MODULE || plan.entryKind()==PhpOwnershipExecution.EntryKind.CHUNK)
            assertTrue(plan.unsupportedLowering().contains("module-logical-frame"));
        else if(plan.entryKind()==PhpOwnershipExecution.EntryKind.FUNCTION
                && plan.unsupportedLowering().equals(List.of("local-reference-promotion-and-rebind"))) {
            // The explicit Session does not admit this plan. Authority validates its distinct physical
            // one-reference-parameter protocol before calling this independent byte/frame comparison.
            assertEquals(1,plan.locals().stream().filter(local->local.importKind()==PhpOwnershipExecution.ImportKind.ARGUMENT
                    && local.importIndex()==0 && local.importMode()==PhpOwnershipExecution.Mode.CELL
                    && local.storage()==PhpOwnershipExecution.Storage.ADDRESS).count());
        }
        else if(plan.unsupportedLowering().isEmpty())exactFrame(plan,member,bytes);
        else {
            assertTrue(plan.entryKind()==PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE || plan.entryKind()==PhpOwnershipExecution.EntryKind.CLASS_ENV_INITIALIZER);
            assertTrue(php.runtime.lang.Closure.class.isAssignableFrom(member.getDeclaringClass()));
            assertEquals(List.of("closure-self-and-static-import"),plan.unsupportedLowering());
        }
        var runtime=PhpRuntimeOwnership.readRuntimePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
        assertNotNull(runtime);assertEquals(List.of(),runtime.unsupported());
        assertEquals(plan.ownerInternalName(),runtime.owner());assertEquals(member.getName(),runtime.method());
        assertEquals(PhpOwnershipExecution.descriptor(member),runtime.descriptor());
    }
    private static void admit(PhpOwnershipExecution.Session session,MethodEntity method)throws Throwable {
        var member=method.getNativeMethod();var bytes=method.getClazz().getData();
        var plan=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));assertNotNull(plan);
        assertEquals(method.getName()+" explicit Session lowering",List.of(),plan.unsupportedLowering());
        session.registerGenerated(method,member,bytes,plan,PhpDeprecatedOwnershipTest::exactFrame);
    }
    private static void arguments(PhpOwnershipExecution.Session session,ModuleEntity module,Entity declaration)throws Throwable {
        for(var attribute:declaration.getPhpAttributes())for(var argument:attribute.arguments())
            session.registerAttributeArgument(module,declaration,attribute,argument,PhpDeprecatedOwnershipTest::exactFrame);
    }
    private static void arguments(PhpRuntimeOwnership.Authority authority,ModuleEntity module,Entity declaration)throws Throwable {
        for(var attribute:declaration.getPhpAttributes())for(var argument:attribute.arguments())
            authority.registerAttributeArgument(module,declaration,attribute,argument,PhpDeprecatedOwnershipTest::exactRuntimeFrame);
    }
    private static void install(PhpOwnershipExecution.Session session,Environment env,TraceInfo trace)throws Throwable {
        try(var callable=session.arrays().allocate(false)) {
            callable.append(session.value(new StringMemory("DepManagedHost")));callable.append(session.value(new StringMemory("handler")));
            try(var roots=session.newCallRoots()){roots.send(callable.binding());roots.invokeFunction(env.fetchFunction("set_error_handler"),trace);}
        }
    }
    private static void restore(PhpOwnershipExecution.Session session,Environment env,TraceInfo trace)throws Throwable {
        try(var roots=session.newCallRoots()){roots.invokeFunction(env.fetchFunction("restore_error_handler"),trace);}
        assertNull(env.getErrorHandler());
    }
    private static void nativeHandlers(PhpRuntimeOwnership.Authority authority)throws Throwable {
        for(var member:LangFunctions.class.getDeclaredMethods())if(Set.of("set_error_handler","restore_error_handler").contains(member.getName()))
            authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){
                    if(member.getName().equals("set_error_handler")){assertTrue(arguments.getFirst().raw() instanceof ObjectMemory);assertNotNull(arguments.getFirst().node());}
                }
            });
    }
    @Test public void sessionDirectMethodWarnsOnceAndReclaimsBothNativePropertyOwners()throws Throwable {
        String source="class DepManagedHost {static function handler($n,$s){echo 'W;';return true;}"
                +"#[Deprecated(message:'owned',since:'v')] static function value($v){echo 'B;';return $v;}}";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,source)) {
            f.module.include(f.env);var participant=f.domain.participant(f.env);var session=new PhpOwnershipExecution.Session(participant,f.env);
            try(var execution=session.enter()) {
                var clazz=f.env.fetchClass("DepManagedHost");var method=clazz.findMethod("value");
                admit(session,method);admit(session,clazz.findMethod("handler"));
                arguments(session,f.module,method);
                install(session,f.env,f.module.getTrace());int live=f.domain.graph().liveNodes();
                for(int repeat=0;repeat<2;repeat++) {
                    try(var roots=session.newCallRoots()) {roots.send(session.value(LongMemory.valueOf(7)));
                        assertEquals(7,session.invokeMethod(method,null,f.module.getTrace(),roots).toLong());}
                    assertNull(PhpOwnershipExecution.currentEntry(f.env));assertEquals("No native object/TABLE/FIELD owner survives return",live,f.domain.graph().liveNodes());
                }
                var nativeClass=f.env.fetchClass("Deprecated",false,PhpLanguageProfile.PHP_8_5);
                assertEquals(2,session.objects().provider(nativeClass).properties().size());
                assertThrows("Diagnostic construction must not grant arbitrary native new capability",IllegalArgumentException.class,()->session.requireMethodCapability(nativeClass.methodConstruct));
                assertEquals("W;B;W;B;",f.text());restore(session,f.env,f.module.getTrace());
            }finally{participant.close();}
            assertEquals(0,f.domain.graph().liveNodes());
        }
    }
    @Test public void runtimeFunctionPacketsWarnForUsedDiscardedAndVoidCallsExactlyOnce()throws Throwable {
        String source="set_error_handler(function($n,$s){echo 'W;';return true;});"
                +"#[Deprecated('owned',since:'v')] function marked(&$v){$v=9;echo 'F;';return $v;}"
                +"$seed=7;marked($seed);$result=marked($seed);(void)marked($seed);restore_error_handler();return $result;";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,source)) {
            var authority=new PhpRuntimeOwnership.Authority(f.env,f.domain);
            try(var execution=authority.enter()) {
                nativeHandlers(authority);
                for(var closure:f.module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),PhpDeprecatedOwnershipTest::exactRuntimeFrame);
                for(var function:f.module.getFunctions()) {authority.registerFunction(function,function.getData(),PhpDeprecatedOwnershipTest::exactRuntimeFrame);arguments(authority,f.module,function);}
                var member=f.module.getNativeMethod();
                var plan=PhpRuntimeOwnership.readRuntimePlan(f.module.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                assertEquals(3,plan.functionCalls().size());assertTrue(plan.functionCalls().stream().anyMatch(PhpRuntimeOwnership.FunctionCall::discardedResult));
                assertTrue(plan.functionCalls().stream().anyMatch(call->!call.discardedResult()));
                authority.registerModule(f.module,f.module.getData(),PhpDeprecatedOwnershipTest::exactRuntimeFrame);
                try(var result=authority.include(f.module)){assertEquals(9,result.raw().toLong());}
                var actual=authority.globalAddress("seed").getReferenceForBinding();
                assertEquals("Actual compiler ADDRESS SEND writes the caller's canonical CELL",9,actual.toLong());
                assertNotNull(actual.getRuntimeCell());assertEquals(1,actual.getRuntimeCell().node().incoming());
                assertEquals("W;F;W;F;W;F;",f.text());assertNull(f.env.getErrorHandler());assertNull(authority.currentFrame());
            }finally{authority.close();}
            assertEquals(0,f.domain.graph().liveNodes());
        }
    }
    @Test public void runtimeClosurePacketsKeepNoDiscardAndDoNotDuplicateDeprecated()throws Throwable {
        String source="set_error_handler(function($n,$s){echo 'W;';return true;});"
                +"$c=#[Deprecated('owned',since:'v'),NoDiscard('discard')] function(){echo 'C;';return 7;};"
                +"$c();$v=$c();(void)$c();restore_error_handler();return $v;";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,source)) {
            var authority=new PhpRuntimeOwnership.Authority(f.env,f.domain);
            try(var execution=authority.enter()) {
                nativeHandlers(authority);
                for(var closure:f.module.getClosures()) {var method=closure.findMethod("__invoke");
                    authority.registerMethod(method,closure.getData(),PhpDeprecatedOwnershipTest::exactRuntimeFrame);arguments(authority,f.module,method);}
                authority.registerModule(f.module,f.module.getData(),PhpDeprecatedOwnershipTest::exactRuntimeFrame);
                var member=f.module.getNativeMethod();
                var plan=PhpRuntimeOwnership.readRuntimePlan(f.module.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                assertTrue(plan.calls().stream().anyMatch(PhpRuntimeOwnership.CallSite::discardedResult));
                assertTrue(plan.calls().stream().anyMatch(call->!call.discardedResult()));
                try(var result=authority.include(f.module)){assertEquals(7,result.raw().toLong());}
                assertEquals("Three Deprecated plus one NoDiscard warning", "W;W;C;W;C;W;C;",f.text());
                assertNotNull(authority.objects().provider(f.env.fetchClass("NoDiscard",false,PhpLanguageProfile.PHP_8_5)));
                assertEquals(2,authority.objects().provider(f.env.fetchClass("Deprecated",false,PhpLanguageProfile.PHP_8_5)).properties().size());
                assertNull(f.env.getErrorHandler());assertNull(authority.currentFrame());
            }finally{authority.close();}
            assertEquals(0,f.domain.graph().liveNodes());
        }
    }
    @Test public void missingSecondInitializerAndForeignOccurrenceFailBeforeAnyBodyOrAllocation()throws Throwable {
        String source="class DepMissing {#[Deprecated('one',since:'v')] static function one($v){echo 'BAD;';return $v;}"
                +"#[Deprecated('two')] static function two($v){return $v;}}";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,source)) {
            f.module.include(f.env);var participant=f.domain.participant(f.env);var session=new PhpOwnershipExecution.Session(participant,f.env);
            try(var execution=session.enter()) {
                var clazz=f.env.fetchClass("DepMissing");var one=clazz.findMethod("one");var two=clazz.findMethod("two");admit(session,one);
                for(int admitted=0;admitted<2;admitted++) {
                    if(admitted==1){var attribute=one.getPhpAttributes().getFirst();session.registerAttributeArgument(f.module,one,attribute,attribute.arguments().getFirst(),PhpDeprecatedOwnershipTest::exactFrame);}
                    try(var roots=session.newCallRoots()) {roots.send(session.value(LongMemory.valueOf(7)));
                        assertThrows(IllegalArgumentException.class,()->session.invokeMethod(one,null,f.module.getTrace(),roots));}
                    assertEquals("",f.text());assertEquals(0,f.domain.graph().liveNodes());assertNull(PhpOwnershipExecution.currentEntry(f.env));
                }
                var attribute=one.getPhpAttributes().getFirst();var argument=attribute.arguments().getLast();
                assertThrows(IOException.class,()->session.registerAttributeArgument(f.module,two,attribute,argument,(p,m,b)->fail("Foreign declaration reached certification")));
                var forged=new PhpAttribute(attribute.name(),attribute.trace(),attribute.target(),attribute.strictTypes(),attribute.arguments());
                assertThrows(IOException.class,()->session.registerAttributeArgument(f.module,one,forged,argument,(p,m,b)->fail("Equal metadata must not become occurrence authority")));
                assertEquals("",f.text());assertEquals(0,f.domain.graph().liveNodes());
            }finally{participant.close();}
        }
    }
    @Test public void exactNativeConstructorPublishesTwoFieldsButWrongTicketsStayRejected()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"return 0;")) {
            var participant=f.domain.participant(f.env);var session=new PhpOwnershipExecution.Session(participant,f.env);
            try(var execution=session.enter()) {
                var clazz=f.env.fetchClass("Deprecated",false,PhpLanguageProfile.PHP_8_5);var objects=session.objects();
                var constructor=PhpDeprecatedAttribute.class.getConstructor(Environment.class,ClassEntity.class);
                byte[] original=php.runtime.reflection.helper.PhpNativePropertiesShellVerifier.require(constructor);
                var provider=objects.registerPropertiesProvider(clazz,constructor,(actual,env)->{
                    assertSame(clazz,actual);assertSame(f.env,env);
                    try{assertArrayEquals(original,php.runtime.reflection.helper.PhpNativePropertiesShellVerifier.require(constructor));}
                    catch(IOException failure){throw new AssertionError(failure);}
                });
                var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
                for(var property:provider.properties())defaults.put(property,session.value(Memory.UNDEFINED));
                try(var allocation=objects.allocate(provider,f.env,defaults)) {
                    var object=(PhpDeprecatedAttribute)allocation.object();Memory[] raw={new StringMemory("message"),new StringMemory("v")};
                    assertThrows(IllegalArgumentException.class,()->PhpDeprecatedOwnedInvocation.publishProperties(object,f.env,raw));
                    try(var roots=session.newCallRoots()) {roots.stageReceiver(session.value(Memory.NULL));for(var value:raw)roots.send(session.value(value));
                        assertThrows(IllegalArgumentException.class,()->session.invokeNativePropertyConstructor(clazz.methodConstruct,object,f.module.getTrace(),roots));}
                    assertTrue(object.getProperties().getByScalar("message").isUndefined());assertTrue(object.getProperties().getByScalar("since").isUndefined());
                    try(var roots=session.newCallRoots()) {roots.stageReceiver(allocation.binding());for(var value:raw)roots.send(session.value(value));
                        assertTrue(session.invokeNativePropertyConstructor(clazz.methodConstruct,object,f.module.getTrace(),roots).isNull());}
                    assertEquals("message",object.getProperties().valueOfIndex("message").toString());assertEquals("v",object.getProperties().valueOfIndex("since").toString());
                    assertTrue(clazz.findProperty("message").isPhpReadonly());assertTrue(clazz.findProperty("since").isPhpReadonly());
                    assertThrows(IllegalArgumentException.class,()->session.requireMethodCapability(clazz.methodConstruct));
                }
                assertEquals(0,f.domain.graph().liveNodes());assertEquals("",f.text());assertNull(PhpOwnershipExecution.currentEntry(f.env));
            }finally{participant.close();}
            assertEquals(0,f.domain.graph().liveNodes());
        }
    }
    @Test public void explicitSessionReferenceImportRemainsUnadmitted()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,
                "class DepSessionBoundary {#[Deprecated('reference')] static function ref(&$v){$v=9;return $v;}}")) {
            f.module.include(f.env);var participant=f.domain.participant(f.env);var session=new PhpOwnershipExecution.Session(participant,f.env);
            try(var execution=session.enter()) {
                var method=f.env.fetchClass("DepSessionBoundary").findMethod("ref");var member=method.getNativeMethod();var bytes=method.getClazz().getData();
                var plan=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));assertNotNull(plan);
                assertEquals(member.getDeclaringClass().getName().replace('.','/'),plan.ownerInternalName());
                assertEquals(member.getName(),plan.javaMethod());assertEquals(PhpOwnershipExecution.descriptor(member),plan.descriptor());
                assertTrue(plan.unsupportedLowering().contains("local-reference-promotion-and-rebind"));
                assertThrows(IllegalArgumentException.class,()->session.registerGenerated(method,member,bytes,plan,(p,m,b)->fail("Unsupported Session reference import reached certification")));
                assertEquals("",f.text());assertEquals(0,f.domain.graph().liveNodes());
            }finally{participant.close();}
        }
    }
    @Test public void byValueNamedCallIsNotImplicitlyCertifiedAsReferencePacket()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,
                "function ordinary($v){echo 'BAD;';return $v;}$seed=1;ordinary($seed);")) {
            var authority=new PhpRuntimeOwnership.Authority(f.env,f.domain);
            try(var execution=authority.enter()) {
                var function=f.env.fetchFunction("ordinary");assertFalse(function.getParameters()[0].isReference());
                authority.registerFunction(function,function.getData(),PhpDeprecatedOwnershipTest::exactRuntimeFrame);
                var member=f.module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(f.module.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));assertNotNull(plan);
                assertTrue("By-value call is not a physical reference FunctionPacket",plan.functionCalls().isEmpty());
                assertThrows(IllegalArgumentException.class,()->authority.registerModule(f.module,f.module.getData(),(p,m,b)->fail("Unconnected by-value caller reached certification")));
                assertEquals("",f.text());assertEquals(0,f.domain.graph().liveNodes());
            }finally{authority.close();}
        }
    }
}
