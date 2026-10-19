package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;
public class PhpNoDiscardTest extends JvmCompilerCase {
    private static final String KIT="nodiscard-staged";
    private static final String ORIGINAL="nodiscard-original";
    private Environment env(ByteArrayOutputStream output,ByteArrayOutputStream stderr,boolean debug){
        var scope=newScope();scope.setDebugMode(debug);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        var env=new Environment(scope,output);
        env.defineConstant("STDERR",php.runtime.memory.ObjectMemory.valueOf(new php.runtime.ext.core.classes.stream.MiscStream(env,stderr)),true);
        return env;
    }
    private static void publish(Environment env,php.runtime.reflection.ModuleEntity module){
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
    }
    private static php.runtime.reflection.ModuleEntity compile(Environment env,Context context,boolean dumped,boolean debug)throws Throwable{
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
        if(dumped){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
        return module;
    }
    private static PhpNativeTestResources.Fixture fixture(String group,String directory,String name)throws IOException {
        var files=new ArrayList<String>();String entry=directory+"/"+name+".php";
        files.add(entry);files.add("native/helper.php");
        if(name.equals("strict-caller"))files.add("native-order/weak-declarations.php");
        if(name.equals("weak-caller"))files.add("native-order/strict-declarations.php");
        return PhpNativeTestResources.treeFixture(group,entry,files.toArray(String[]::new));
    }
    private void exact(String group,String directory,String oracle,String name)throws Throwable{
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})try(var fixture=fixture(group,directory,name)){
            var output=new ByteArrayOutputStream();var stderr=new ByteArrayOutputStream();var env=env(output,stderr,debug);
            try{
                var context=new Context(PhpNativeTestResources.text(group+"/"+directory+"/"+name+".php"),fixture.file());
                var module=compile(env,context,dumped,debug);env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                publish(env,module);module.include(env);env.flushAll();
                String label=name+" debug="+debug+" PHB="+dumped+" actual="+output.toString(StandardCharsets.UTF_8);
                assertArrayEquals(label,PhpNativeTestResources.read(group+"/"+oracle+"/"+name+".stdout"),PhpNativeTestResources.remapNoDiscardDiagnostics(output.toByteArray(),fixture,group));
                assertArrayEquals(label+" genuine stderr",PhpNativeTestResources.read(group+"/"+oracle+"/"+name+".stderr"),PhpNativeTestResources.remapNoDiscardDiagnostics(stderr.toByteArray(),fixture,group));
            }finally{env.doFinal();}
        }
    }
    @Test public void originalNativeRoutesWarningHandlerAndInheritanceMatchExactBytes()throws Throwable{
        for(String name:new String[]{"nodiscard-call-routes","nodiscard-warning-order","nodiscard-inheritance"})
            exact(ORIGINAL,"native","native-root-r1",name);
    }
    @Test public void closuresGeneratorsReferencesNativeDispatchAndMessageObjectMatchExactBytes()throws Throwable{
        for(String name:new String[]{"routes-extra","closure-generator","constructor-contract","argument-warning-order","delayed-target"})
            exact(KIT,"native","native-root-r1",name);
    }
    @Test public void strictOccurrenceCallerAndBindingFailureOrderingMatchExactBytes()throws Throwable{
        exact(KIT,"native-strict","native-strict-root-r1","strict-message");
        for(String name:new String[]{"binding-order","weak-caller","strict-caller","class-closure"})
            exact(KIT,"native-order","native-order-root-r1",name);
    }
    @Test public void illegalTargetsKindsMagicAndVoidExpressionsFailBeforeBody()throws Throwable{
        var names=new ArrayList<String>();
        PhpNativeTestResources.text(KIT+"/negative-cases.list").lines().filter(s->!s.isBlank()).forEach(names::add);
        assertEquals("all pinned negative controls",12,names.size());
        names.addAll(List.of("negative-nodiscard-void","negative-delayed-nodiscard-void","negative-nodiscard-constructor","negative-void-expression"));
        for(String name:names){
            var output=new ByteArrayOutputStream();var env=env(output,new ByteArrayOutputStream(),true);
            try{
                boolean original=name.startsWith("negative-nodiscard-")||name.equals("negative-delayed-nodiscard-void")||name.equals("negative-void-expression");
                String group=original?ORIGINAL:KIT;
                String source=PhpNativeTestResources.capturedDiagnosticContext(group,"native/"+name+".php");
                String stderr=PhpNativeTestResources.text(group+"/native-root-r1/"+name+".stderr");
                String prefix=stderr.startsWith("Parse error:")?"Parse error: ":"Fatal error: ";int from=stderr.indexOf(prefix),to=stderr.indexOf(" in "+source,from);
                assertTrue(name+" native exact diagnostic",from>=0&&to>from);String expected=stderr.substring(from+prefix.length(),to);
                try(var fixture=fixture(group,"native",name)){
                    var context=new Context(PhpNativeTestResources.text(group+"/native/"+name+".php"),fixture.file());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    try{new JvmCompiler(env,context).compile(false);fail("Accepted "+name);}
                    catch(php.runtime.exceptions.support.ErrorException rejected){assertEquals(name,expected,rejected.getMessage());}
                }
                assertEquals("no body or argument initializer",0,output.size());
            }finally{env.doFinal();}
        }
    }
    @Test public void nativeProviderAndJavaAliasesKeepLegacyAndPhpReflectionIsolated()throws Throwable{
        var output=new ByteArrayOutputStream();var env=env(output,new ByteArrayOutputStream(),false);
        try{
            assertNull(env.fetchClass("NoDiscard",false,PhpLanguageProfile.JPHP_LEGACY));
            var context=new Context("class NoDiscard{} echo function_exists('call_user_funcDiscarded')?'bad':'ok';");context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var old=new JvmCompiler(env,context).compile(false);publish(env,old);old.include(env);
            var legacy=env.fetchClass("NoDiscard",false,PhpLanguageProfile.JPHP_LEGACY);
            var modern=env.fetchClass("NoDiscard",false,PhpLanguageProfile.PHP_8_5);assertNotSame(legacy,modern);
            assertEquals(php.runtime.ext.core.classes.PhpNoDiscardAttribute.class,modern.getNativeClass());
            assertSame(legacy,env.fetchClass("NoDiscard",false,PhpLanguageProfile.JPHP_LEGACY));
            var check=new Context("echo function_exists('call_user_funcDiscarded')?'bad':'ok'; echo function_exists('call_user_func_arrayDiscarded')?'bad':'ok'; foreach(get_defined_functions()['internal'] as $n){if(stripos($n,'Discarded')!==false)echo 'bad';} echo (new ReflectionFunction('call_user_func'))->getName();");
            var module=compile(env,check,true,false);publish(env,module);module.include(env);env.flushAll();
            assertEquals("okokokcall_user_func",output.toString(StandardCharsets.UTF_8));
        }finally{env.doFinal();}
    }
    @Test public void voidCastConsumesResultAndOldFrameLayoutAndDescriptorsRemain()throws Throwable{
        var output=new ByteArrayOutputStream();var env=env(output,new ByteArrayOutputStream(),false);
        try{
            var context=new Context("#[NoDiscard] function value(){echo 'body';return 1;}set_error_handler(function(){echo 'warning';});(void)value();");
            var module=compile(env,context,true,false);publish(env,module);module.include(env);env.flushAll();assertEquals("body",output.toString(StandardCharsets.UTF_8));
            var fieldNames=new TreeSet<String>();for(var field:php.runtime.invoke.PhpCallFrame.class.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers()))fieldNames.add(field.getName());
            assertEquals("exact existing frame instance fields",new TreeSet<>(List.of("env","trace","function","invoker","parameters","nativeMethod","originClass","originMethod","arguments","size","capacity","nativeTarget","binder","bound","closed","invoked","constructedObject","ownershipRoots","ownershipEntry")),fieldNames);
            assertNotNull(php.runtime.invoke.PhpCallFrame.class.getMethod("invoke"));
            assertNotNull(php.runtime.invoke.PhpCallFrame.class.getMethod("invokePrepared",php.runtime.Memory[].class));
            assertNotNull(php.runtime.invoke.InvokeHelper.class.getMethod("call",Environment.class,TraceInfo.class,php.runtime.reflection.FunctionEntity.class,php.runtime.Memory[].class,boolean.class));
            assertNotNull(php.runtime.invoke.ObjectInvokeHelper.class.getMethod("invokeMethod",php.runtime.lang.IObject.class,php.runtime.reflection.MethodEntity.class,Environment.class,TraceInfo.class,php.runtime.Memory[].class,boolean.class,boolean.class));
            assertEquals(ModuleDumper.COMPILER_ABI_VERSION,module.getCompilerProvenance().getCompilerAbiVersion());
        }finally{env.doFinal();}
    }
    @Test public void forgedVoidTargetRejectsBeforeProviderConstructorAndBody()throws Throwable{
        for(boolean debug:new boolean[]{false,true}){
            var writer=env(new ByteArrayOutputStream(),new ByteArrayOutputStream(),debug);
            var output=new ByteArrayOutputStream();var reader=env(output,new ByteArrayOutputStream(),debug);
            try{
                var context=new Context("#[NoDiscard] function good(){return 1;} function nope():void{} class Child extends ExternalParent{} echo 'body';");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(writer,context).compile(false);
                var bytes=new ByteArrayOutputStream();new ModuleDumper(context,writer,debug).save(module,bytes);byte[] corrupt=bytes.toByteArray();
                String from="F/"+module.findFunction("good").getInternalName().replace('/','.');String to="F/"+module.findFunction("nope").getInternalName().replace('/','.');
                assertEquals(from.length(),to.length());byte[] key=from.getBytes(StandardCharsets.UTF_8),replacement=to.getBytes(StandardCharsets.UTF_8);int found=-1;
                for(int at=0;at+key.length<=corrupt.length;at++)if(Arrays.equals(Arrays.copyOfRange(corrupt,at,at+key.length),key)){assertEquals(-1,found);found=at;}
                assertTrue(found>=0);System.arraycopy(replacement,0,corrupt,found,replacement.length);
                var setup=compile(reader,new Context("spl_autoload_register(function($n){echo 'autoload';eval('class ExternalParent{}');});"),false,debug);publish(reader,setup);setup.include(reader);reader.flushAll();assertEquals(0,output.size());
                int registered=reader.getScope().moduleIndexMap.size();
                try{new ModuleDumper(context,reader,debug).load(new ByteArrayInputStream(corrupt));fail("Accepted forged NoDiscard void declaration");}
                catch(DumpException rejected){assertEquals("A void function does not return a value, but #[\\NoDiscard] requires a return value",rejected.getMessage());}
                reader.flushAll();assertEquals(0,output.size());assertEquals(registered,reader.getScope().moduleIndexMap.size());assertNull(reader.fetchClass("ExternalParent",false,PhpLanguageProfile.PHP_8_5));
            }finally{writer.doFinal();reader.doFinal();}
        }
    }
}
