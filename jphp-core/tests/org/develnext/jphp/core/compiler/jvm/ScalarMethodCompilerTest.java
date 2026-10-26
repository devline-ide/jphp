package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.core.compiler.jvm.statement.ScalarMethodCompiler;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.PhpPropertyWriteContext;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.util.*;

import static org.junit.Assert.*;
import static org.objectweb.asm.Opcodes.*;

/** Real PHP calls and saved PHB, with bytecode assertions on the primitive bodies actually emitted. */
public class ScalarMethodCompilerTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final Environment env;
        final ModuleEntity module;
        final Memory result;
        final ClassEntity clazz;
        Fixture(String methods,String expression,PhpOptimizationProfile profile,boolean dumped,boolean strict,boolean debug)throws Throwable {
            var scope=newScope();scope.setDebugMode(debug);
            env=new Environment(scope,new ByteArrayOutputStream());
            Context context=new Context((strict?"declare(strict_types=1); ":"")+"final class ScalarFixture { "+methods+" } return "+expression+";");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);context.setPhpOptimizationProfile(profile);
            ModuleEntity compiled=new JvmCompiler(env,context).compile(false);
            if(dumped){
                var dumper=new ModuleDumper(context,env,true);var output=new ByteArrayOutputStream();dumper.save(compiled,output);
                compiled=dumper.load(new ByteArrayInputStream(output.toByteArray()));
            }
            module=compiled;
            // Emitted arithmetic/profile is owned by the artifact, not the subsequent scope default.
            scope.setPhpOptimizationProfile(profile==PhpOptimizationProfile.COMPATIBLE?PhpOptimizationProfile.FAST_INT64_WRAP:PhpOptimizationProfile.COMPATIBLE);
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            result=module.include(env);clazz=env.fetchClass("ScalarFixture",false,PhpLanguageProfile.PHP_8_5);
            assertNotNull(clazz);assertNull(PhpPropertyWriteContext.current(env));
            StringWriter diagnostics=new StringWriter();
            CheckClassAdapter.verify(new ClassReader(clazz.getData()),scope.getClassLoader(),false,new PrintWriter(diagnostics));
            assertEquals("Invalid generated JVM code", "",diagnostics.toString());
        }
        ClassNode bytes(){ClassNode node=new ClassNode();new ClassReader(clazz.getData()).accept(node,0);return node;}
        MethodNode kernel(String name){return bytes().methods.stream().filter(m->m.name.equals("__$scalar$"+name+"$"+module.getOptimizationProfile().getId())).findFirst().orElse(null);}
        @Override public void close()throws Exception {try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }

    @Test public void typedArithmeticLocalsComparisonsAndBooleanSourceAndPhb()throws Throwable {
        String methods="public static function add(int $a,int $b):int{return $a+$b;} "
                +"public static function weighted(int $a,int $b):int{$sum=$a+$b;return $sum*3-1;} "
                +"public static function blend(float $a,float $b):float{return -$a+$b*2.0;} "
                +"public static function positive(int $a):bool{return $a>0;} "
                +"public static function negate(bool $v):bool{return !$v;} "
                +"public static function widened(int $a):float{return $a;}";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,
                "[ScalarFixture::add(2,3),ScalarFixture::weighted(2,3),ScalarFixture::blend(2.5,4.0),ScalarFixture::positive(-1),ScalarFixture::negate(false),ScalarFixture::widened(7),ScalarFixture::add(b:4,a:3)]",profile,dumped,false,false)){
            assertEquals(5,f.result.valueOfIndex(null,0).toLong());assertEquals(14,f.result.valueOfIndex(null,1).toLong());
            assertEquals(5.5,f.result.valueOfIndex(null,2).toDouble(),0);assertFalse(f.result.valueOfIndex(null,3).toBoolean());
            assertTrue(f.result.valueOfIndex(null,4).toBoolean());assertEquals(Memory.Type.DOUBLE,f.result.valueOfIndex(null,5).toValue().type);
            assertEquals(7,f.result.valueOfIndex(null,6).toLong());
            for(String name:List.of("add","weighted","blend","positive","negate","widened"))assertNotNull(name,f.kernel(name));
            MethodNode add=f.kernel("add");assertEquals("(JJ)J",add.desc);
            if(profile==PhpOptimizationProfile.FAST_INT64_WRAP){
                assertEquals(List.of(LLOAD,LLOAD,LADD,LRETURN),opcodes(add));
            }else assertTrue(calls(add,"java/lang/Math","addExact"));
            for(MethodNode method:f.bytes().methods)if(method.name.startsWith("__$scalar$")){
                assertFalse(calls(method,"php/runtime/PhpPropertyWriteContext","enter"));
                assertFalse(calls(method,"php/runtime/PhpOwnershipExecution","enterGenerated"));
                assertFalse(calls(method,"php/runtime/PhpRuntimeOwnership","enterGenerated"));
                assertFalse(calls(method,"php/runtime/PhpArithmetic","binary"));
            }
            var original=f.clazz.findMethod("add").getNativeMethod();
            assertFalse(original.getName().startsWith(ScalarMethodCompiler.ENTRY_PREFIX));
            assertEquals(Memory.class,original.getReturnType());
            assertNotNull(original.getDeclaringClass().getDeclaredMethod(ScalarMethodCompiler.ENTRY_PREFIX+original.getName(),Environment.class,Memory[].class));
        }
    }

    @Test public void compatibleOverflowFallsBackAndFastModeWrapsWithoutLeakingContext()throws Throwable {
        String methods="public static function add(int $a,int $b):int{return $a+$b;} "
                +"public static function product(int $a,int $b):int{return $a*$b;} "
                +"public static function negative(int $a):int{return -$a;}";
        String expression="(function(){ $out=[]; foreach(['add','product','negative'] as $name){try{"
                +"$out[]=$name==='negative'?ScalarFixture::negative(PHP_INT_MIN):($name==='add'?ScalarFixture::add(PHP_INT_MAX,1):ScalarFixture::product(PHP_INT_MAX,2));"
                +"}catch(\\TypeError $e){$out[]='type';}} return $out;})()";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,expression,profile,dumped,true,false)){
            for(int i=0;i<3;i++){
                Memory value=f.result.valueOfIndex(null,i);
                if(profile==PhpOptimizationProfile.COMPATIBLE)assertEquals("type",value.toString());
                else assertEquals(i==1?-2:Long.MIN_VALUE,value.toLong());
            }
            assertNull(PhpPropertyWriteContext.current(f.env));
        }
    }

    @Test public void floatingNaNInfinitySignedZeroAndStrictIdentityKeepPhpSemantics()throws Throwable {
        String methods="public static function lt(float $a,float $b):bool{return $a<$b;} "
                +"public static function le(float $a,float $b):bool{return $a<=$b;} "
                +"public static function gt(float $a,float $b):bool{return $a>$b;} "
                +"public static function ge(float $a,float $b):bool{return $a>=$b;} "
                +"public static function same(float $a,float $b):bool{return $a===$b;} "
                +"public static function different(float $a,float $b):bool{return $a!==$b;} "
                +"public static function negative(float $a):float{return -$a;} "
                +"public static function mixedIdentity(int $a,float $b):bool{return $a===$b;} "
                +"public static function mixedEqual(int $a,float $b):bool{return $a==$b;}";
        String expression="[ScalarFixture::lt(NAN,0.0),ScalarFixture::le(NAN,0.0),ScalarFixture::gt(NAN,0.0),ScalarFixture::ge(NAN,0.0),"
                +"ScalarFixture::same(NAN,NAN),ScalarFixture::different(NAN,NAN),ScalarFixture::lt(INF,INF),ScalarFixture::le(INF,INF),"
                +"ScalarFixture::same(-0.0,0.0),ScalarFixture::negative(0.0),ScalarFixture::negative(-0.0),"
                +"ScalarFixture::mixedIdentity(1,1.0),ScalarFixture::mixedEqual(1,1.0)]";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,expression,profile,dumped,true,false)){
            for(int i=0;i<5;i++)assertFalse("NaN comparison "+i,f.result.valueOfIndex(null,i).toBoolean());
            assertTrue(f.result.valueOfIndex(null,5).toBoolean());assertFalse(f.result.valueOfIndex(null,6).toBoolean());
            assertTrue(f.result.valueOfIndex(null,7).toBoolean());assertTrue(f.result.valueOfIndex(null,8).toBoolean());
            assertEquals(Double.doubleToRawLongBits(-0.0d),Double.doubleToRawLongBits(f.result.valueOfIndex(null,9).toDouble()));
            assertEquals(Double.doubleToRawLongBits(0.0d),Double.doubleToRawLongBits(f.result.valueOfIndex(null,10).toDouble()));
            assertFalse(f.result.valueOfIndex(null,11).toBoolean());assertTrue(f.result.valueOfIndex(null,12).toBoolean());
            for(String name:List.of("lt","le","gt","ge","same","different","negative","mixedequal"))assertNotNull(name,f.kernel(name));
            assertNull("Different primitive kinds cannot use numeric equality for strict identity",f.kernel("mixedidentity"));
        }
    }

    @Test public void strictAndWeakArgumentRulesRemainOutsideScalarEntry()throws Throwable {
        String method="public static function add(int $a,int $b):int{return $a+$b;}";
        String expression="(function(){try{return ScalarFixture::add('2',3);}catch(\\TypeError $e){return 'type';}})()";
        for(boolean strict:new boolean[]{false,true})for(var profile:PhpOptimizationProfile.values())try(var f=new Fixture(method,expression,profile,false,strict,false)){
            if(strict)assertEquals("type",f.result.toString());else assertEquals(5,f.result.toLong());
            assertNotNull(f.kernel("add"));
        }
    }

    @Test public void foreignScalarSubclassUsesOriginalBodyRatherThanPrimitiveUnboxing()throws Throwable {
        try(var f=new Fixture("public static function add(int $a,int $b):int{return $a+$b;}","0",PhpOptimizationProfile.COMPATIBLE,true,false,false)){
            var method=f.clazz.findMethod("add").getNativeMethod();
            var entry=method.getDeclaringClass().getDeclaredMethod(ScalarMethodCompiler.ENTRY_PREFIX+method.getName(),Environment.class,Memory[].class);
            int[] converted={0};
            Memory foreign=new php.runtime.memory.LongMemory(2){
                @Override public Memory toValue(){converted[0]++;return php.runtime.memory.LongMemory.valueOf(2);}
            };
            Memory result=(Memory)entry.invoke(null,f.env,new Memory[]{foreign,php.runtime.memory.LongMemory.valueOf(3)});
            assertEquals(5,result.toLong());assertTrue("Custom Memory callbacks must stay on the ordinary PHP path",converted[0]>0);
            assertNull(PhpPropertyWriteContext.current(f.env));
        }
    }

    @Test public void unsupportedEffectsReferencesDefaultsAndControlFlowKeepOriginalLowering()throws Throwable {
        String methods="public static int $value=0; "
                +"public static function untyped($a,$b){return $a+$b;} "
                +"public static function reference(int &$a):int{return $a+1;} "
                +"public static function optional(int $a=1):int{return $a+1;} "
                +"public static function nullable(?int $a):int{return $a+1;} "
                +"public static function write(int $a):int{self::$value=$a;return $a;} "
                +"public static function cleanup(int $a):int{try{return $a;}finally{self::$value=$a;}} "
                +"#[\\Deprecated] public static function old(int $a):int{return $a+1;} "
                +"public static function recursive(int $a):int{return self::recursive($a);}";
        try(var f=new Fixture(methods,"0",PhpOptimizationProfile.COMPATIBLE,true,false,false)){
            assertTrue(f.bytes().methods.stream().noneMatch(m->m.name.startsWith("__$scalar$")));
            assertTrue(f.bytes().methods.stream().noneMatch(m->m.name.startsWith(ScalarMethodCompiler.ENTRY_PREFIX)));
        }
        try(var f=new Fixture("public static function add(int $a,int $b):int{return $a+$b;}","ScalarFixture::add(1,2)",PhpOptimizationProfile.FAST_INT64_WRAP,false,false,true)){
            assertNull(f.kernel("add"));assertEquals(3,f.result.toLong());
        }
    }

    private static List<Integer> opcodes(MethodNode method){return Arrays.stream(method.instructions.toArray()).map(AbstractInsnNode::getOpcode).filter(op->op>=0).toList();}
    private static boolean calls(MethodNode method,String owner,String name){return Arrays.stream(method.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call && call.owner.equals(owner)&&call.name.equals(name));}
}
