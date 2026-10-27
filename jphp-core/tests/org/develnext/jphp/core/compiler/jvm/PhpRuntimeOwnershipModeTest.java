package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.common.PhpRuntimeOwnershipMode;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Selected ordinary semantics and explicit admission boundaries, not universal PHP equivalence. */
public class PhpRuntimeOwnershipModeTest extends JvmCompilerCase {
    private Context context(String code, PhpRuntimeOwnershipMode mode) {
        Context context=new Context(code);
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);context.setPhpOwnershipMode(mode);
        return context;
    }
    private ModuleEntity load(Environment env,String code,PhpRuntimeOwnershipMode mode,boolean dumped)throws Throwable {
        Context context=context(code,mode);ModuleEntity module=new JvmCompiler(env,context).compile(false);
        if(dumped) {
            var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
            dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);
        assertSame(mode,module.getOwnershipMode());return module;
    }
    private List<byte[]> bodies(ModuleEntity module) {
        var result=new ArrayList<byte[]>();result.add(module.getData());
        module.getFunctions().forEach(value->result.add(value.getData()));
        module.getClasses().forEach(value->result.add(value.getData()));
        module.getDeferredClasses().forEach(value->result.add(value.getData()));
        module.getClosures().forEach(value->result.add(value.getData()));
        module.getGenerators().forEach(value->result.add(value.getData()));return result;
    }
    private void assertNoOwnershipInstrumentation(ModuleEntity module) {
        for(byte[] bytes:bodies(module))if(bytes!=null) {
            String constants=new String(bytes,StandardCharsets.ISO_8859_1);
            assertFalse("OFF leaked ordinary ownership helpers",constants.contains("php/runtime/PhpRuntimeOwnership"));
            assertFalse("OFF leaked Session helpers",constants.contains("php/runtime/PhpOwnershipExecution"));
            assertFalse("OFF leaked frame transport",constants.contains("JPHP_Ownership_Frame_v1"));
            assertFalse("OFF leaked runtime transport",constants.contains("JPHP_Runtime_Frame_v1"));
        }
    }

    @Test public void defaultsOverridesCopiesAndSourceCacheIdentity()throws Throwable {
        var scope=newScope();assertSame(PhpRuntimeOwnershipMode.AUTO,scope.getPhpOwnershipMode());
        var context=new Context("return 1;");scope.setPhpOwnershipMode(PhpRuntimeOwnershipMode.OFF);
        assertSame(PhpRuntimeOwnershipMode.OFF,context.getPhpOwnershipMode(scope));
        context.setPhpOwnershipMode(PhpRuntimeOwnershipMode.CHECKED);
        assertSame(PhpRuntimeOwnershipMode.CHECKED,context.getPhpOwnershipMode(scope));
        assertSame(PhpRuntimeOwnershipMode.OFF,new CompileScope(scope).getPhpOwnershipMode());
        assertThrows(IllegalArgumentException.class,()->PhpRuntimeOwnershipMode.fromId(99));
        for(var mode:PhpRuntimeOwnershipMode.values())assertSame(mode,PhpRuntimeOwnershipMode.fromId(mode.getId()));
        assertNotEquals(new ModuleCacheKey("memory://one.php",false,PhpLanguageProfile.PHP_8_5,"",PhpOptimizationProfile.COMPATIBLE,PhpRuntimeOwnershipMode.AUTO),
                new ModuleCacheKey("memory://one.php",false,PhpLanguageProfile.PHP_8_5,"",PhpOptimizationProfile.COMPATIBLE,PhpRuntimeOwnershipMode.OFF));
        assertEquals(new ModuleCacheKey("memory://one.phb",true,PhpLanguageProfile.PHP_8_5,"",PhpOptimizationProfile.COMPATIBLE,PhpRuntimeOwnershipMode.AUTO),
                new ModuleCacheKey("memory://one.phb",true,PhpLanguageProfile.JPHP_LEGACY,"",PhpOptimizationProfile.FAST_INT64_WRAP,PhpRuntimeOwnershipMode.OFF));
        var env=new Environment(newScope(),OutputStream.nullOutputStream());
        try {
            Context a=context("return 1;",PhpRuntimeOwnershipMode.AUTO),b=context("return 1;",PhpRuntimeOwnershipMode.OFF);
            // A named source context exercises Environment/CompileScope caches, not just key equality.
            var ac=new Context(new ByteArrayInputStream("<?php return 1;".getBytes(StandardCharsets.UTF_8)),"memory://mode.php",StandardCharsets.UTF_8);
            ac.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);ac.setPhpOwnershipMode(a.getPhpOwnershipMode(null));
            var bc=new Context(new ByteArrayInputStream("<?php return 1;".getBytes(StandardCharsets.UTF_8)),"memory://mode.php",StandardCharsets.UTF_8);
            bc.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);bc.setPhpOwnershipMode(b.getPhpOwnershipMode(null));
            assertNotSame(env.importModule(ac),env.importModule(bc));
        }finally{env.doFinal();}
    }

    @Test public void ordinaryReferencesCowClosuresHooksDestructorsAndFinallyAcrossSourcePhb()throws Throwable {
        String code="$GLOBALS['destroyed']=0; $GLOBALS['finally']=0; "+
                "class ModeBox { public int $n=2 { get { return $this->n; } set { $this->n=$value+1; } } "+
                "public function __destruct(){ $GLOBALS['destroyed']++; } } "+
                "function typedFailure():int { try { return 'bad'; } finally { $GLOBALS['finally']++; } } "+
                "$a=[1,2]; $b=$a; $b[0]=7; $r=&$a[1]; $r=9; $c=function()use(&$r){$r++;return $r;}; "+
                "$v=$c(); $box=new ModeBox; $box->n=4; $n=$box->n; unset($box); "+
                "$caught=false;try{typedFailure();}catch(\\TypeError $e){$caught=true;} "+
                "return [$a[0],$a[1],$b[0],$b[1],$v,$n,$GLOBALS['destroyed'],$GLOBALS['finally'],$caught];";
        for(boolean dumped:new boolean[]{false,true})for(var mode:PhpRuntimeOwnershipMode.values()) {
            var env=new Environment(newScope(),OutputStream.nullOutputStream());
            try {
                ModuleEntity module=load(env,code,mode,dumped);
                env.scope.setPhpOwnershipMode(mode==PhpRuntimeOwnershipMode.OFF?PhpRuntimeOwnershipMode.CHECKED:PhpRuntimeOwnershipMode.OFF);
                Memory result=module.include(env);
                long[] expected={1,10,7,2,10,5,1,1};
                for(int i=0;i<expected.length;i++)assertEquals(mode+" PHB="+dumped+" slot="+i,expected[i],result.valueOfIndex(null,i).toLong());
                assertTrue(result.valueOfIndex(null,8).toBoolean());
                assertNull(PhpPropertyWriteContext.current(env));
                if(mode==PhpRuntimeOwnershipMode.OFF)assertNoOwnershipInstrumentation(module);
            }finally{env.doFinal();}
        }
    }

    @Test public void checkedRetainsOrdinaryInstrumentedAbiAndOffKeepsNumericPolicy()throws Throwable {
        for(var mode:PhpRuntimeOwnershipMode.values())for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(false);var env=new Environment(scope,OutputStream.nullOutputStream());
            try {
                ModuleEntity module=load(env,"final class ModeScalar { public static function add(int $a,int $b):int{return $a+$b;} } return ModeScalar::add(2,3);",mode,dumped);
                assertEquals(5,module.include(env).toLong());
                var clazz=env.fetchClass("ModeScalar",false,PhpLanguageProfile.PHP_8_5);var physical=new ClassNode();new ClassReader(clazz.getData()).accept(physical,0);
                assertEquals(mode!=PhpRuntimeOwnershipMode.CHECKED,physical.methods.stream().anyMatch(method->method.name.startsWith("__$scalarEntry$")));
                String constants=new String(clazz.getData(),StandardCharsets.ISO_8859_1);
                assertEquals(mode!=PhpRuntimeOwnershipMode.OFF,constants.contains("php/runtime/PhpRuntimeOwnership"));
            }finally{env.doFinal();}
        }
        for(var numeric:PhpOptimizationProfile.values()) {
            var env=new Environment(newScope(),OutputStream.nullOutputStream());
            try {
                Context context=context("return 9223372036854775807+1;",PhpRuntimeOwnershipMode.OFF);context.setPhpOptimizationProfile(numeric);
                var module=new JvmCompiler(env,context).compile(false);env.scope.loadModule(module);env.registerModule(module);
                Memory result=module.include(env);
                assertEquals(numeric==PhpOptimizationProfile.COMPATIBLE?Memory.Type.DOUBLE:Memory.Type.INT,result.type);
                if(numeric==PhpOptimizationProfile.FAST_INT64_WRAP)assertEquals(Long.MIN_VALUE,result.toLong());
                assertNoOwnershipInstrumentation(module);
            }finally{env.doFinal();}
        }
    }

    @Test public void evalUsesLexicalModuleModeInsteadOfLaterScopeDefault()throws Throwable {
        for(var mode:PhpRuntimeOwnershipMode.values())for(boolean dumped:new boolean[]{false,true}) {
            var env=new Environment(newScope(),OutputStream.nullOutputStream());
            try {
                ModuleEntity module=load(env,"eval('class EvalMode {}'); return 7;",mode,dumped);
                env.scope.setPhpOwnershipMode(mode==PhpRuntimeOwnershipMode.OFF?PhpRuntimeOwnershipMode.CHECKED:PhpRuntimeOwnershipMode.OFF);
                assertEquals(7,module.include(env).toLong());
                assertSame(mode,env.fetchClass("EvalMode",false,PhpLanguageProfile.PHP_8_5).getModule().getOwnershipMode());
            }finally{env.doFinal();}
        }
    }

    @Test public void offAdmissionAndActiveExecutionFailClosedAcrossSourceAndPhb()throws Throwable {
        for(boolean dumped:new boolean[]{false,true}) {
            var env=new Environment(newScope(),OutputStream.nullOutputStream());var domain=new PhpOwnershipDomain();
            ModuleEntity module=load(env,"function modeFunction(){return 4;} class ModeAdmission { public static function value():int{return 5;} } return static function(){return 6;};",PhpRuntimeOwnershipMode.OFF,dumped);
            Memory closure=module.include(env);var callback=php.runtime.invoke.Invoker.valueOf(env,TraceInfo.UNKNOWN,closure);
            assertEquals(6,callback.call().toLong());
            var function=env.fetchFunction("modeFunction");var method=env.fetchClass("ModeAdmission",false,PhpLanguageProfile.PHP_8_5).findMethod("value");
            var participant=domain.participant(env);var session=new PhpOwnershipExecution.Session(participant,env);
            try {
                try(var execution=session.enter()) {
                    assertThrows(IllegalArgumentException.class,()->session.registerGenerated(function,function.getNativeMethod(),function.getData(),null,(plan,member,bytes)->{}));
                    assertThrows(IllegalArgumentException.class,()->module.include(env));
                    assertThrows(IllegalArgumentException.class,()->function.invoke(env,null,new Memory[0]));
                    assertThrows(IllegalArgumentException.class,()->method.invokeStatic(env));
                    assertThrows(IllegalArgumentException.class,()->callback.call());
                }
            }finally{try(var execution=participant.enter()){participant.close();}}
            var authority=new PhpRuntimeOwnership.Authority(env,new PhpOwnershipDomain());
            try(var execution=authority.enter()) {
                assertThrows(IllegalArgumentException.class,()->authority.registerFunction(function,function.getData(),(plan,member,bytes)->{}));
                assertThrows(IllegalArgumentException.class,()->authority.registerMethod(method,method.getClazz().getData(),(plan,member,bytes)->{}));
                assertThrows(IllegalArgumentException.class,()->authority.registerModule(module,module.getData(),(plan,member,bytes)->{}));
                assertThrows(IllegalArgumentException.class,()->module.include(env));
                assertThrows(IllegalArgumentException.class,()->function.invoke(env,null,new Memory[0]));
                assertThrows(IllegalArgumentException.class,()->method.invokeStatic(env));
                assertThrows(IllegalArgumentException.class,()->callback.call());
            }finally{authority.close();env.doFinal();}
        }
    }

    @Test public void physicalOffTraitHookRetainsDonorContractUnderCheckedConsumer()throws Throwable {
        for(boolean dumped:new boolean[]{false,true}) {
            var env=new Environment(newScope(),OutputStream.nullOutputStream());var domain=new PhpOwnershipDomain();
            try {
                load(env,"trait OffLeaf { public int $n=2 { get {return $this->n;} set {$this->n=$value+1;} } }",PhpRuntimeOwnershipMode.OFF,dumped).include(env);
                ModuleEntity consumer=load(env,"trait OffMiddle { use OffLeaf; } class CheckedConsumer { use OffMiddle; } $v=new CheckedConsumer; $v->n=8; return $v->n;",PhpRuntimeOwnershipMode.CHECKED,dumped);
                assertEquals(9,consumer.include(env).toLong());
                var clazz=env.fetchClass("CheckedConsumer",false,PhpLanguageProfile.PHP_8_5);var hook=clazz.findProperty("n").getPhpHooks().get().method();
                var receiver=env.getGlobals().getByScalar("v").toValue(php.runtime.memory.ObjectMemory.class).value;
                var property=clazz.findProperty("n");
                var participant=domain.participant(env);var session=new PhpOwnershipExecution.Session(participant,env);
                try(var execution=session.enter()) {
                    assertThrows(IllegalArgumentException.class,()->php.runtime.PhpPropertyHookAccess.read(property,env,TraceInfo.UNKNOWN,receiver,false,null,null));
                }finally{try(var execution=participant.enter()){participant.close();}}
                var authority=new PhpRuntimeOwnership.Authority(env,new PhpOwnershipDomain());
                try(var execution=authority.enter()) {
                    // Consumer mode cannot manufacture the absent physical donor frame plans.
                    assertThrows(IllegalArgumentException.class,()->authority.registerMethod(hook,clazz.getData(),(plan,member,bytes)->{}));
                    assertThrows(IllegalArgumentException.class,()->php.runtime.PhpPropertyHookAccess.read(property,env,TraceInfo.UNKNOWN,receiver,false,null,null));
                }finally{authority.close();}
            }finally{env.doFinal();}
        }
    }

    @Test public void sourceResolverFreezesCopiesAndPhbIgnoresHostPolicy()throws Throwable {
        Path directory=Files.createTempDirectory("jphp-ownership-mode-");Path source=directory.resolve("Mode.php"),dump=directory.resolve("Mode.phb");
        Files.writeString(source,"<?php return 7;",StandardCharsets.UTF_8);var env=new Environment(newScope(),OutputStream.nullOutputStream());
        try {
            AtomicInteger selections=new AtomicInteger();env.getModuleManager().setSourceLanguageProfileResolver(path->PhpLanguageProfile.PHP_8_5);
            env.getModuleManager().setSourceOwnershipModeResolver(path->{selections.incrementAndGet();assertEquals(ModuleCacheKey.normalize(source.toString()),path);return PhpRuntimeOwnershipMode.OFF;});
            ModuleEntity module=env.getModuleManager().fetchModule(source.toString(),false);
            assertSame(module,env.getModuleManager().fetchModule(source.toString(),false));assertEquals(2,selections.get());
            assertSame(PhpRuntimeOwnershipMode.OFF,module.getOwnershipMode());
            assertThrows(IllegalStateException.class,()->env.getModuleManager().setSourceOwnershipModeResolver(path->PhpRuntimeOwnershipMode.CHECKED));
            try(var bytes=Files.newOutputStream(dump)){new ModuleDumper(module.getContext(),env,true).save(module,bytes);}
            var copy=new Environment(newScope(),OutputStream.nullOutputStream());
            try {
                copy.getModuleManager().apply(env.getModuleManager());
                assertThrows(IllegalStateException.class,()->copy.getModuleManager().setSourceOwnershipModeResolver(path->PhpRuntimeOwnershipMode.AUTO));
            }finally{copy.doFinal();}
            var restored=new Environment(newScope(),OutputStream.nullOutputStream());restored.scope.setPhpOwnershipMode(PhpRuntimeOwnershipMode.CHECKED);
            restored.getModuleManager().setSourceOwnershipModeResolver(path->{throw new AssertionError("PHB consulted source resolver");});
            try {
                ModuleEntity artifact=restored.getModuleManager().fetchModule(dump.toString(),true);
                assertSame(PhpRuntimeOwnershipMode.OFF,artifact.getOwnershipMode());assertEquals(7,artifact.include(restored).toLong());
            }finally{restored.doFinal();}
        }finally{env.doFinal();Files.deleteIfExists(dump);Files.deleteIfExists(source);Files.deleteIfExists(directory);}
    }

    @Test public void includeAndAutoloadResolveAuthoredAndSdkPolicyPerFileWithCacheHits()throws Throwable {
        Path directory=Files.createTempDirectory("jphp-ownership-includes-");
        Path owner=directory.resolve("Owner.php"),part=directory.resolve("Part.php"),sdk=directory.resolve("Sdk.php");
        Files.writeString(owner,"<?php spl_autoload_register(function($class){require __DIR__.'/Sdk.php';}); $part=require __DIR__.'/Part.php'; $sdk=new ModeSdk; return [$part,$sdk->read()];",StandardCharsets.UTF_8);
        Files.writeString(part,"<?php class ModePart {} return 5;",StandardCharsets.UTF_8);
        Files.writeString(sdk,"<?php class ModeSdk { public function read(){return 8;} }",StandardCharsets.UTF_8);
        var env=new Environment(newScope(),OutputStream.nullOutputStream());
        var selections=new LinkedHashMap<String,Integer>();String sdkPath=ModuleCacheKey.normalize(sdk.toString());
        try {
            env.getModuleManager().setSourceLanguageProfileResolver(path->path.equals(sdkPath)?PhpLanguageProfile.JPHP_LEGACY:PhpLanguageProfile.PHP_8_5);
            env.getModuleManager().setSourceOwnershipModeResolver(path->{selections.merge(path,1,Integer::sum);return path.equals(sdkPath)?PhpRuntimeOwnershipMode.AUTO:PhpRuntimeOwnershipMode.OFF;});
            ModuleEntity module=env.getModuleManager().fetchModule(owner.toString(),false);assertSame(PhpRuntimeOwnershipMode.OFF,module.getOwnershipMode());
            Memory result=module.include(env);assertEquals(5,result.valueOfIndex(null,0).toLong());assertEquals(8,result.valueOfIndex(null,1).toLong());
            var authored=env.fetchClass("ModePart",false,PhpLanguageProfile.PHP_8_5).getModule();
            var library=env.fetchClass("ModeSdk",false,PhpLanguageProfile.JPHP_LEGACY).getModule();
            assertSame(PhpRuntimeOwnershipMode.OFF,authored.getOwnershipMode());assertSame(PhpRuntimeOwnershipMode.AUTO,library.getOwnershipMode());
            assertSame(PhpLanguageProfile.JPHP_LEGACY,library.getPhpLanguageProfile());
            assertSame(module,env.getModuleManager().fetchModule(owner.toString(),false));
            assertSame(authored,env.getModuleManager().fetchModule(part.toString(),false));
            assertSame(library,env.getModuleManager().fetchModule(sdk.toString(),false));
            assertEquals(Integer.valueOf(2),selections.get(ModuleCacheKey.normalize(owner.toString())));
            assertEquals(Integer.valueOf(2),selections.get(ModuleCacheKey.normalize(part.toString())));
            assertEquals(Integer.valueOf(2),selections.get(sdkPath));
        }finally{env.doFinal();Files.deleteIfExists(owner);Files.deleteIfExists(part);Files.deleteIfExists(sdk);Files.deleteIfExists(directory);}
    }

    @Test public void currentAbiStoresModeAndOldHeadersDefaultAuto()throws Throwable {
        var env=new Environment(newScope(),OutputStream.nullOutputStream());
        try {
            ModuleEntity module=new JvmCompiler(env,context("return 1;",PhpRuntimeOwnershipMode.OFF)).compile(false);
            var bytes=new ByteArrayOutputStream();new ModuleDumper(module.getContext(),env,true).save(module,bytes);
            byte[] encoded=bytes.toByteArray();assertEquals(32,ByteBuffer.wrap(encoded).getInt(8));
            assertSame(PhpRuntimeOwnershipMode.OFF,ModuleDumper.readHeader(new ByteArrayInputStream(encoded)).getOwnershipMode());
            ByteBuffer.wrap(encoded).putInt(20,99);assertThrows(DumpException.class,()->ModuleDumper.readHeader(new ByteArrayInputStream(encoded)));
            for(int abi:new int[]{1,29,30}) {
                bytes.reset();var old=new DataOutputStream(bytes);old.writeInt(ModuleDumper.DUMP_STAMP);old.writeInt(ModuleDumper.DUMP_VERSION);old.writeInt(abi);old.writeInt(PhpLanguageProfile.PHP_8_5.getId());
                if(abi>=30)old.writeInt(PhpOptimizationProfile.COMPATIBLE.getId());
                assertSame(PhpRuntimeOwnershipMode.AUTO,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getOwnershipMode());
            }
        }finally{env.doFinal();}
    }
}
