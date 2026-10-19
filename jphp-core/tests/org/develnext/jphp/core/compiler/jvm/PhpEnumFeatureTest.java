package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.*;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpTypeDeclaration;
import static org.junit.Assert.*;

/** Pinned native PHP 8.5 observations, source/PHB paths and cold carrier admission. */
public class PhpEnumFeatureTest extends JvmCompilerCase {
    private JsonObject controls() throws IOException {
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_enum_controls.json")) {
            assertNotNull(input);return new Gson().fromJson(new String(input.readAllBytes(),StandardCharsets.UTF_8),JsonObject.class);
        }
    }
    private CompileScope scope(boolean debug) {
        CompileScope scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());return scope;
    }
    private ModuleEntity compile(Environment env,Context context,boolean dump) throws Throwable {
        ModuleEntity module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,env.scope.isDebugMode());dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        return module;
    }
    private Context context(String code,PhpLanguageProfile profile) {Context context=new Context(code);context.setPhpLanguageProfile(profile);return context;}
    private void register(Environment env,ModuleEntity module) {env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);}
    private void check(JsonObject row,JsonObject controls,boolean dump,boolean debug) throws Throwable {
        Path directory=Files.createTempDirectory("jphp-enum-owned-fixture-");
        Path support=directory.resolve("_support.php"),loaded=directory.resolve("loaded-enum.php"),fixture=directory.resolve(row.get("id").getAsString()+".php");
        Files.writeString(support,controls.get("support").getAsString(),StandardCharsets.UTF_8);
        Files.writeString(loaded,controls.get("autoload").getAsString(),StandardCharsets.UTF_8);
        String code=row.get("code").getAsString();Files.writeString(fixture,code,StandardCharsets.UTF_8);
        ByteArrayOutputStream output=new ByteArrayOutputStream();Environment env=new Environment(scope(debug),output);env.getDefaultBuffer().setChunkSize(0);
        String mode=row.get("id").getAsString()+" dump="+dump+" debug="+debug;
        try {
            Context context=new Context(code,fixture.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            if(row.get("negative").getAsBoolean()) {
                assertThrows(mode,Throwable.class,()->{ModuleEntity module=compile(env,context,dump);register(env,module);module.include(env);});
                env.getDefaultBuffer().flush();assertEquals(mode,0,output.size());
            }else {
                ModuleEntity module=compile(env,context,dump);register(env,module);module.include(env);env.getDefaultBuffer().flush();
                String actual=output.toString(StandardCharsets.UTF_8).replace("\r\n","\n");
                // Preserve the oracle's diagnostic payload/line; remap only this exact owned fixture path.
                JsonObject nativeNames=new Gson().fromJson(PhpNativeTestResources.text("enums/context-names.json"),JsonObject.class);
                String nativeFixture=nativeNames.get(row.get("id").getAsString()).getAsString();
                actual=actual.replace(fixture.toString().replace("\\","\\\\"),nativeFixture.replace("\\","\\\\"));
                assertEquals(mode,row.get("stdout").getAsString(),actual);
            }
        }finally {try{env.doFinal();}finally{Files.deleteIfExists(fixture);Files.deleteIfExists(loaded);Files.deleteIfExists(support);Files.delete(directory);}}
    }
    @Test public void native54ControlsSourceAndPHBDebugBothModes() throws Throwable {
        JsonObject controls=controls();assertEquals(54,controls.getAsJsonArray("cases").size());
        List<Throwable> failures=new ArrayList<>();
        for(JsonElement entry:controls.getAsJsonArray("cases"))for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            try{check(entry.getAsJsonObject(),controls,dump,debug);}catch(Throwable failure){failures.add(new AssertionError(entry.getAsJsonObject().get("id").getAsString()+" dump="+dump+" debug="+debug,failure));}
        }
        if(!failures.isEmpty()){AssertionError failure=new AssertionError(failures.size()+" enum controls failed");for(Throwable item:failures)failure.addSuppressed(item);throw failure;}
    }
    @Test public void unadmittedExplicitOwnershipRejectsBeforeCaseInitializationAndCanRetryOutsideSession() throws Throwable {
        Environment env=new Environment(scope(true),new ByteArrayOutputStream());
        try {
            ModuleEntity module=compile(env,context("enum OwnershipCase:int{case A=MISSING_ENUM_BACKING;}",PhpLanguageProfile.PHP_8_5),true);register(env,module);module.include(env);
            ClassEntity declaration=module.findClass("OwnershipCase");ConstantEntity constant=declaration.findConstant("A");
            PhpOwnershipDomain domain=new PhpOwnershipDomain();var participant=domain.participant(env);var session=new PhpOwnershipExecution.Session(participant,env);
            try(var execution=session.enter()) {
                assertThrows(IllegalArgumentException.class,()->constant.getValue(env));assertThrows(IllegalArgumentException.class,()->PhpEnums.cases(env,"OwnershipCase"));
                assertNull(env.getEnumState(declaration,false));assertEquals(0,domain.graph().liveNodes());participant.close();
            }
            env.defineConstant("MISSING_ENUM_BACKING",Memory.CONST_INT_1,true);assertEquals(1,((php.runtime.lang.BaseEnum)((ObjectMemory)constant.getValue(env)).value).enumBackingValue().toLong());
        }finally{env.doFinal();}
    }
    @Test public void canonicalCasesAreOwnedByEnvironmentAndRetiredCacheDoesNotAffectAnotherEnvironment() throws Throwable {
        for(boolean dump:new boolean[]{false,true}) {
            CompileScope scope=scope(true);Environment first=new Environment(scope,new ByteArrayOutputStream()),second=new Environment(scope,new ByteArrayOutputStream());
            ClassEntity declaration=null;
            try {
                ModuleEntity module=compile(first,context("enum OwnedCase:string {case A='a';case B='b';} const NEIGHBOR=['x'=>[7,8]];",PhpLanguageProfile.PHP_8_5),dump);
                register(first,module);module.include(first);second.registerModule(module);module.include(second);
                declaration=module.findClass("OwnedCase");ConstantEntity constant=declaration.findConstant("A");
                ObjectMemory a=(ObjectMemory)constant.getValue(first),b=(ObjectMemory)constant.getValue(second);
                assertNotSame(a.value,b.value);assertSame(first,a.value.getEnvironment());assertSame(second,b.value.getEnvironment());
                assertSame(a.value,((ObjectMemory)constant.getValue(first)).value);assertSame(b.value,((ObjectMemory)constant.getValue(second)).value);
                assertSame(a.value,PhpEnums.cases(first,"OwnedCase").valueOfIndex(0).toValue(ObjectMemory.class).value);
                var cases=PhpEnums.cases(first,"OwnedCase").toValue(php.runtime.memory.ArrayMemory.class);cases.remove(Memory.CONST_INT_0);
                assertEquals(2,PhpEnums.cases(first,"OwnedCase").toValue(php.runtime.memory.ArrayMemory.class).size());
                assertNotNull(first.getEnumState(declaration,false));first.doFinal();assertNull(first.getEnumState(declaration,false));
                assertSame(b.value,((ObjectMemory)constant.getValue(second)).value);
                assertThrows(IllegalStateException.class,()->constant.getValue(first));
            }finally {first.doFinal();second.doFinal();}
        }
    }
    @Test public void sharedScopeLegacyNamesCannotPoisonModernEnumBuiltinsAndLegacySyntaxRejectsBeforeEffects() throws Throwable {
        CompileScope scope=scope(true);ByteArrayOutputStream output=new ByteArrayOutputStream();Environment env=new Environment(scope,output);
        try {
            ModuleEntity legacy=compile(env,context("class UnitEnum{} class BackedEnum{} class ReflectionEnum{} function enum_exists($name){return 'legacy';} class enum{}",PhpLanguageProfile.JPHP_LEGACY),false);
            register(env,legacy);legacy.include(env);
            assertSame(legacy.findClass("UnitEnum"),env.fetchClass("UnitEnum",false,PhpLanguageProfile.JPHP_LEGACY));
            ClassEntity modern=env.fetchClass("UnitEnum",false,PhpLanguageProfile.PHP_8_5);
            assertNotSame(legacy.findClass("UnitEnum"),modern);assertTrue(modern.isInterface());
            assertNotSame(legacy.findClass("ReflectionEnum"),env.fetchClass("ReflectionEnum",false,PhpLanguageProfile.PHP_8_5));
            ModuleEntity module=compile(env,context("enum CleanCase{case A;}echo enum_exists('CleanCase')?'modern':'bad';",PhpLanguageProfile.PHP_8_5),true);
            register(env,module);module.include(env);env.getDefaultBuffer().flush();assertEquals("modern",output.toString(StandardCharsets.UTF_8));
            output.reset();assertThrows(Throwable.class,()->compile(env,context("echo 'effect';enum Blocked{case A;}",PhpLanguageProfile.JPHP_LEGACY),false));assertEquals(0,output.size());
        }finally{env.doFinal();}
    }
    @Test public void coldCorruptionRejectsGeneratedCarrierBeforeAutoloadOrModuleEffects() throws Throwable {
        for(String mutation:List.of("superclass","helper","owner","argument-type","return-type","readonly-property","initializer-owner","initializer-method","case-flags","case-kind")) {
            ByteArrayOutputStream output=new ByteArrayOutputStream();Environment env=new Environment(scope(true),output);int[] autoload={0};
            env.registerAutoloader(new SplClassLoader(null,Memory.NULL){@Override public boolean load(Memory...args){autoload[0]++;return false;}},false);
            try {
                Context context=context("enum ColdEnum:int{case A=UNKNOWN_BACKING;case B=2;}echo 'effect';",PhpLanguageProfile.PHP_8_5);
                ModuleEntity module=compile(env,context,false);ClassEntity declaration=module.findClass("ColdEnum");
                MethodEntity from=declaration.findMethod("from");ConstantEntity constant=declaration.findConstant("A");
                if(mutation.equals("argument-type"))from.getParameters()[0].setTypeChecker(new PhpTypeDeclaration(List.of(List.of("mixed"))));
                else if(mutation.equals("return-type"))from.setReturnTypeChecker(new PhpTypeDeclaration(List.of(List.of("mixed"))));
                else if(mutation.equals("readonly-property"))declaration.findProperty("name").setPhpReadonly(false);
                else if(mutation.equals("initializer-owner"))constant.setInitializer("ColdForeign",constant.getInitializerMethod());
                else if(mutation.equals("initializer-method"))constant.setInitializer(constant.getInitializerClass(),"missing");
                else if(mutation.equals("case-flags"))constant.setFinal(true);
                else if(mutation.equals("case-kind"))constant.setEnumCase(false);
                else {
                    ClassNode physical=new ClassNode();new ClassReader(declaration.getData()).accept(physical,0);
                    if(mutation.equals("superclass"))physical.superName="php/runtime/lang/BaseObject";
                    else for(MethodNode method:physical.methods)if(method.name.equals(from.getInternalName()))for(AbstractInsnNode instruction:method.instructions) {
                        if(mutation.equals("helper") && instruction instanceof MethodInsnNode call)call.owner="php/runtime/ForeignEnum";
                        if(mutation.equals("owner") && instruction instanceof LdcInsnNode owner)owner.cst="ForeignEnum";
                    }
                    ClassWriter writer=new ClassWriter(0);physical.accept(writer);declaration.setData(writer.toByteArray());
                }
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();ModuleDumper dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);
                assertThrows(mutation,DumpException.class,()->dumper.load(new ByteArrayInputStream(bytes.toByteArray())));
                assertEquals(mutation,0,autoload[0]);assertEquals(mutation,0,output.size());assertNull(env.getEnumState(declaration,false));
            }finally{env.doFinal();}
        }
    }
}
