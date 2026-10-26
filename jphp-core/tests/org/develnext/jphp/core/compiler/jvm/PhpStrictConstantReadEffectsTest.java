package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

/** Native-pinned ordinary reads and defaults; the explicit Session lane uses real generated declarations. */
public class PhpStrictConstantReadEffectsTest {
    @BeforeClass public static void physicalOrigins()throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(31,ModuleDumper.class.getField("COMPILER_ABI_VERSION").getInt(null));
    }
    private static byte[] resource(String name)throws Exception {
        try(var input=PhpStrictConstantReadEffectsTest.class.getClassLoader().getResourceAsStream("strict-read-focused/"+name)) {
            assertNotNull(name,input);return input.readAllBytes();
        }
    }
    private static CompileScope scope(boolean debug) {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());return scope;
    }
    private static ModuleEntity compile(Environment env,String source,boolean phb,boolean debug,PhpLanguageProfile profile)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(profile);var module=new JvmCompiler(env,context).compile(false);
        assertEquals(31,module.getCompilerProvenance().getCompilerAbiVersion());
        if(phb) {
            var bytes=new ByteArrayOutputStream();var dump=new ModuleDumper(context,env,debug);dump.save(module,bytes);
            assertEquals(31,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());
            module=dump.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        assertEquals(profile,module.getPhpLanguageProfile());
        var scope=env.getScope();scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static void nativeCases(String catalog)throws Throwable {
        int cells=0;
        for(String id:new String(resource(catalog+".list"),StandardCharsets.UTF_8).split("\\R"))if(!id.isBlank()) {
            String source=new String(resource(id+".php"),StandardCharsets.UTF_8);assertTrue(source.startsWith("<?php"));source=source.substring(5);
            byte[] expected=resource(id+".stdout");
            for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
                var out=new ByteArrayOutputStream();var env=new Environment(scope(debug),out);
                try {compile(env,source,phb,debug,PhpLanguageProfile.PHP_8_5).include(env);env.flushAll();assertArrayEquals(id+" debug="+debug+" phb="+phb,expected,out.toByteArray());}
                finally {env.doFinal();}
                cells++;System.out.println("CASEPASS "+id+" debug="+debug+" phb="+phb);
            }
        }
        assertEquals(catalog.equals("reads")?28:48,cells);
    }
    @Test(timeout=120000) public void directDynamicNamespaceProbeAndPublicationOrderMatchPinnedNative()throws Throwable {nativeCases("reads");}
    @Test(timeout=120000) public void scalarArrayReentryRetryAndOrdinaryNewDefaultsMatchPinnedNative()throws Throwable {nativeCases("defaults");}
    @Test(timeout=20000) public void legacyStrictReadRemainsSilentInSourceAndRealPhb()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();var env=new Environment(scope(debug),out);
            try {compile(env,"error_reporting(-1);set_error_handler(function($n,$s){echo 'WRONG;';return true;});echo E_STRICT;echo constant('E_STRICT');",phb,debug,PhpLanguageProfile.JPHP_LEGACY).include(env);env.flushAll();assertEquals("20482048",out.toString(StandardCharsets.UTF_8));}
            finally {env.doFinal();}
        }
    }
    private static void admit(PhpOwnershipExecution.Session session,Object entity,java.lang.reflect.Method member,byte[] bytes)throws Throwable {
        var plan=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
        assertNotNull(plan);assertEquals(List.of(),plan.unsupportedLowering());
        session.registerGenerated(entity,member,bytes,plan,(actual,physical,defined)->{
            var reread=PhpOwnershipExecution.readFramePlan(defined,physical.getName(),PhpOwnershipExecution.descriptor(physical));
            assertNotNull(reread);assertEquals(plan.ownerInternalName(),reread.ownerInternalName());assertEquals(plan.javaMethod(),reread.javaMethod());
            assertEquals(plan.descriptor(),reread.descriptor());assertEquals(List.of(),reread.unsupportedLowering());
            assertArrayEquals(plan.evalIds(),reread.evalIds());assertEquals(plan.requiredCapabilities(),reread.requiredCapabilities());
            assertTrue(reread.requiredCapabilities().stream().allMatch(capability->Set.of("frame-local-publication-v1","literal-buffered-output","empty-tick-handler").contains(capability)));
        });
    }
    @Test(timeout=20000) public void activeSessionScalarDefaultCachesAfterFirstWarningAndKeepsExactInitializerTransfer()throws Throwable {
        String source="class StrictManaged {static function value($x=E_STRICT){return $x;}static function handler($n,$s){echo 'W;';return true;}}";
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();var env=new Environment(scope(debug),out);env.getDefaultBuffer().setChunkSize(0);env.setErrorFlags(-1);
            var domain=new PhpOwnershipDomain();var participant=domain.participant(env);Throwable primary=null;
            try {
                var module=compile(env,source,phb,debug,PhpLanguageProfile.PHP_8_5);module.include(env);
                var clazz=env.fetchClass("StrictManaged");var method=clazz.findMethod("value");var handler=clazz.findMethod("handler");
                var parameter=method.getParameters()[0];var initializer=parameter.getDefaultInitializer();assertNotNull(initializer);assertTrue(initializer.constantOnly());assertEquals("E_STRICT",parameter.getDefaultValueConstName());
                var session=new PhpOwnershipExecution.Session(participant,env);
                try(var execution=session.enter()) {
                    admit(session,method,method.getNativeMethod(),clazz.getData());admit(session,handler,handler.getNativeMethod(),clazz.getData());
                    Class<?> owner=env.getScope().getClassLoader().loadClass(initializer.javaClass());
                    var member=owner.getDeclaredMethod(initializer.method(),Environment.class);
                    byte[] bytes=((php.runtime.loader.RuntimeClassLoader)owner.getClassLoader()).definedInputBytes(owner);
                    admit(session,parameter,member,bytes);
                    try(var callable=session.arrays().allocate(false)) {
                        callable.append(session.value(new php.runtime.memory.StringMemory("StrictManaged")));callable.append(session.value(new php.runtime.memory.StringMemory("handler")));
                        try(var roots=session.newCallRoots()){roots.send(callable.binding());roots.invokeFunction(env.fetchFunction("set_error_handler"),module.getTrace());}
                    }
                    int before=domain.graph().liveNodes();
                    for(int repeat=0;repeat<2;repeat++)try(var roots=session.newCallRoots()) {
                        assertEquals(2048,session.invokeMethod(method,null,module.getTrace(),roots).toLong());assertNull(PhpOwnershipExecution.currentEntry(env));
                    }
                    assertEquals(before,domain.graph().liveNodes());env.flushAll();assertEquals("W;",out.toString(StandardCharsets.UTF_8));
                    try(var roots=session.newCallRoots()){roots.invokeFunction(env.fetchFunction("restore_error_handler"),module.getTrace());}
                    assertNull(env.getErrorHandler());assertEquals(0,domain.graph().liveNodes());
                }
            } catch(Throwable failure) {primary=failure;throw failure;}
            finally {
                Throwable cleanup=null;
                try {env.doFinal();} catch(Throwable failure) {cleanup=failure;}
                try {participant.close();} catch(Throwable failure) {
                    if(cleanup==null)cleanup=failure;else if(cleanup!=failure)cleanup.addSuppressed(failure);
                }
                if(cleanup!=null) {if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}
            }
            assertEquals(0,domain.graph().liveNodes());
        }
    }
    @Test(timeout=20000) public void activeSessionShortVectorsPrepareLiteralAndSuppliedScalarAndOwnRequiredError()throws Throwable {
        String source="class StrictHost {static function literal($x=2){return $x;}static function pair(int $x,$y=2){return $x;}static function required($x){echo 'BAD;';return $x;}}";
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();var env=new Environment(scope(debug),out);env.getDefaultBuffer().setChunkSize(0);
            var domain=new PhpOwnershipDomain();var participant=domain.participant(env);Throwable primary=null;
            try {
                var module=compile(env,source,phb,debug,PhpLanguageProfile.PHP_8_5);module.include(env);
                var callTrace=new TraceInfo(module.getContext());assertSame(module.getContext(),callTrace.getContext());assertEquals(PhpLanguageProfile.JPHP_LEGACY,env.getScope().getPhpLanguageProfile());assertEquals(PhpLanguageProfile.PHP_8_5,env.getModuleManager().getPhpLanguageProfile(callTrace));
                var clazz=env.fetchClass("StrictHost");var literal=clazz.findMethod("literal");var pair=clazz.findMethod("pair");var required=clazz.findMethod("required");
                var session=new PhpOwnershipExecution.Session(participant,env);var observed=new StringBuilder();
                try(var execution=session.enter()) {
                    admit(session,literal,literal.getNativeMethod(),clazz.getData());admit(session,pair,pair.getNativeMethod(),clazz.getData());admit(session,required,required.getNativeMethod(),clazz.getData());
                    try(var roots=session.newCallRoots()) {observed.append(session.invokeMethod(literal,null,callTrace,roots).toLong()).append(';');}
                    assertNull(PhpOwnershipExecution.currentEntry(env));assertEquals(0,domain.graph().liveNodes());
                    try(var roots=session.newCallRoots()) {
                        roots.send(session.value(new php.runtime.memory.StringMemory("40")));
                        Memory result=session.invokeMethod(pair,null,callTrace,roots);assertEquals(Memory.Type.INT,result.getRealType());observed.append(result.toLong()).append(';');
                    }
                    assertNull(PhpOwnershipExecution.currentEntry(env));assertEquals(0,domain.graph().liveNodes());
                    PhpOwnership.Node failureNode=null;
                    try(var roots=session.newCallRoots()) {
                        try {session.invokeMethod(required,null,callTrace,roots);fail("Missing required argument reached generated body");}
                        catch(php.runtime.lang.exception.BaseArgumentCountError error) {
                            assertEquals(php.runtime.lang.exception.BaseArgumentCountError.class,error.getClass());
                            var certificate=PhpOwnershipThrowables.certificate(error);assertNotNull(certificate);
                            var binding=roots.failureBinding(error);assertNotNull(binding);failureNode=certificate.node();assertSame(failureNode,binding.node());
                            assertEquals(PhpOwnership.State.LIVE,failureNode.state());assertSame(error,((php.runtime.memory.ObjectMemory)binding.rawValue()).value);
                            observed.append(error.getReflection().getName()).append(':').append(error.getMessage()).append(';');
                        }
                    }
                    assertNotNull(failureNode);assertEquals(PhpOwnership.State.DISPOSED,failureNode.state());assertNull(PhpOwnershipExecution.currentEntry(env));
                    assertEquals(0,domain.graph().liveNodes());env.flushAll();assertEquals("",out.toString(StandardCharsets.UTF_8));
                    assertArrayEquals(resource("host-short-vector.stdout"),observed.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch(Throwable failure) {primary=failure;throw failure;}
            finally {
                Throwable cleanup=null;
                try {env.doFinal();} catch(Throwable failure) {cleanup=failure;}
                try {participant.close();} catch(Throwable failure) {if(cleanup==null)cleanup=failure;else if(cleanup!=failure)cleanup.addSuppressed(failure);}
                if(cleanup!=null) {if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}
            }
            assertEquals(0,domain.graph().liveNodes());
        }
    }
}
