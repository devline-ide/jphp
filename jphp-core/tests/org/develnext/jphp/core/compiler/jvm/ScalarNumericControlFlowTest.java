package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.PhpPropertyWriteContext;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.common.PhpRuntimeOwnershipMode;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ClassEntity;

import java.io.*;
import java.util.*;

import static org.junit.Assert.*;
import static org.objectweb.asm.Opcodes.*;

/** Structured-flow kernels are exercised through PHP dispatch and PHB, never only through a synthetic JVM entry. */
public class ScalarNumericControlFlowTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final Environment env;
        final ClassEntity clazz;
        final Memory result;
        final PhpOptimizationProfile profile;
        Fixture(String methods,String expression,PhpOptimizationProfile profile,boolean dumped)throws Throwable {
            this(methods,expression,profile,dumped,PhpRuntimeOwnershipMode.AUTO);
        }
        Fixture(String methods,String expression,PhpOptimizationProfile profile,boolean dumped,PhpRuntimeOwnershipMode ownership)throws Throwable {
            this.profile=profile;var scope=newScope();scope.setDebugMode(false);
            env=new Environment(scope,new ByteArrayOutputStream());
            Context context=new Context("declare(strict_types=1); final class NumericFlow {"+methods+"} return "+expression+";");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);context.setPhpOptimizationProfile(profile);context.setPhpOwnershipMode(ownership);
            var module=new JvmCompiler(env,context).compile(false);
            if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);result=module.include(env);
            clazz=env.fetchClass("NumericFlow",false,PhpLanguageProfile.PHP_8_5);assertNotNull(clazz);
            StringWriter diagnostics=new StringWriter();CheckClassAdapter.verify(new ClassReader(clazz.getData()),scope.getClassLoader(),false,new PrintWriter(diagnostics));
            assertEquals("Invalid scalar control-flow bytecode", "",diagnostics.toString());assertNull(PhpPropertyWriteContext.current(env));
        }
        MethodNode kernel(String name){var node=new ClassNode();new ClassReader(clazz.getData()).accept(node,0);return node.methods.stream().filter(method->method.name.equals("__$scalar$"+name+"$"+profile.getId())).findFirst().orElse(null);}
        void supported(String... names){for(String name:names){MethodNode method=kernel(name);assertNotNull(name,method);
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call)
                assertFalse("Primitive kernel restored boxed bookkeeping: "+call.owner,call.owner.startsWith("php/runtime/"));
        }}
        @Override public void close()throws Exception{try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }

    @Test public void loopsBranchesCompoundUpdatesAndEarlyReturnsAcrossSourceAndPhb()throws Throwable {
        String methods="public static function sum(int $n):int{$sum=0;for($i=0;$i<$n;$i++){$sum+=$i;}return $sum;}"
                +"public static function whileLoop(int $n):int{$sum=0;$i=$n;while($i>0){--$i;if(($i&1)===0){continue;}$sum+=$i;if($sum>20){break;}}return $sum;}"
                +"public static function doLoop(int $n):int{$i=0;do{$i++;if($i<2){continue;}}while($i<$n);return $i;}"
                +"public static function branch(int $n):int{if($n>0){$value=$n*2;}else{$value=3;}return $value;}"
                +"public static function early(int $n):int{if($n<0){return -1;}$value=0;for($i=0;$i<$n;++$i){if($i===3){return $value;}$value+=$i;}return $value;}"
                +"public static function compound(int $n):int{$x=$n;$x+=3;$x-=2;$x*=4;return $x;}"
                +"public static function inferred(int $n){$x=0;while($n>0){$x+=$n;$n--;}return $x;}";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,
                "[NumericFlow::sum(10),NumericFlow::sum(0),NumericFlow::whileLoop(10),NumericFlow::doLoop(0),NumericFlow::doLoop(5),"
                        +"NumericFlow::branch(4),NumericFlow::branch(-4),NumericFlow::early(-2),NumericFlow::early(10),NumericFlow::early(2),NumericFlow::compound(2),NumericFlow::inferred(5)]",profile,dumped)){
            long[] expected={45,0,21,1,5,8,3,-1,3,1,12,15};
            for(int i=0;i<expected.length;i++)assertEquals("row "+i+" "+profile+" PHB="+dumped,expected[i],f.result.valueOfIndex(null,i).toLong());
            f.supported("sum","whileloop","doloop","branch","early","compound","inferred");
            assertTrue(Arrays.stream(f.kernel("sum").instructions.toArray()).anyMatch(instruction->instruction.getOpcode()==GOTO));
        }
    }

    @Test public void nestedBreakAndContinueLevelsTargetCorrectLoop()throws Throwable {
        String methods="public static function continueOuter():int{$sum=0;for($i=0;$i<4;$i++){for($j=0;$j<4;$j++){if($j===1){continue 2;}$sum+=1;}}return $sum;}"
                +"public static function breakOuter():int{$sum=0;for($i=0;$i<5;$i++){for($j=0;$j<5;$j++){if($i===2 && $j===1){break 2;}$sum+=1;}}return $sum;}"
                +"public static function nestedWhile():int{$i=0;$sum=0;while($i<4){$i++;$j=0;while($j<4){$j++;if($j===2){continue 2;}$sum+=1;}}return $sum;}";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,
                "[NumericFlow::continueOuter(),NumericFlow::breakOuter(),NumericFlow::nestedWhile()]",profile,dumped)){
            assertEquals(4,f.result.valueOfIndex(null,0).toLong());assertEquals(11,f.result.valueOfIndex(null,1).toLong());assertEquals(4,f.result.valueOfIndex(null,2).toLong());
            f.supported("continueouter","breakouter","nestedwhile");
        }
    }

    @Test public void definiteAssignmentAndStableKindsGateControlFlow()throws Throwable {
        String methods="public static function oneBranch(int $n):int{if($n>0){$x=2;}return $x;}"
                +"public static function loopOnly(int $n):int{for($i=0;$i<$n;$i++){$x=$i;}return $x;}"
                +"public static function changingKind(int $n):float{$x=1;while($n>0){$x+=0.5;$n--;}return $x;}"
                +"public static function updateReadsBody(int $n):int{for($i=0;$i<$n;$i=$step){$step=$i+1;}return $i;}"
                +"public static function effects(int $n):int{$x=0;while($n>0){echo $n;$n--;}$x=1;return $x;}"
                +"public static function assignedAfterReturn(int $n):int{if($n<0){return -1;}else{$x=2;}return $x;}";
        for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,"NumericFlow::assignedAfterReturn(1)",PhpOptimizationProfile.COMPATIBLE,dumped)){
            for(String name:List.of("onebranch","looponly","changingkind","updatereadsbody","effects"))assertNull(name,f.kernel(name));
            f.supported("assignedafterreturn");assertEquals(2,f.result.toLong());
        }
    }

    @Test public void compatibleOverflowRestartsOnlyPureMethodAndPreservesTypedError()throws Throwable {
        String methods="public static function accumulate(int $start,int $n):int{$value=$start;for($i=0;$i<$n;$i++){$value+=1;}return $value;}"
                +"public static function below(int $start):int{while($start>0){$start*=2;}return $start;}";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,
                "(function(){try{return NumericFlow::accumulate(PHP_INT_MAX-1,2);}catch(\\TypeError $e){return 'type';}})()",profile,dumped)){
            f.supported("accumulate","below");
            if(profile==PhpOptimizationProfile.COMPATIBLE)assertEquals("type",f.result.toString());else assertEquals(Long.MIN_VALUE,f.result.toLong());
        }
    }

    @Test public void integerBitwiseAndOutOfRangeShiftsPreserveOrdinaryForkRule()throws Throwable {
        String methods="public static function bits(int $x):int{$x&=15;$x|=32;$x^=7;return (~$x)&255;}"
                +"public static function shl(int $a,int $b):int{return $a<<$b;}"
                +"public static function shr(int $a,int $b):int{return $a>>$b;}";
        String expression=
                "[NumericFlow::bits(42),NumericFlow::shl(1,0),NumericFlow::shl(1,63),NumericFlow::shl(1,64),NumericFlow::shl(1,65),NumericFlow::shr(-1,63),NumericFlow::shr(-1,64),NumericFlow::shr(4,64),"
                        +"(function(){try{return NumericFlow::shl(1,-1);}catch(\\ArithmeticError $e){return 'shift';}})()]";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var baseline=new Fixture(methods,expression,profile,dumped,PhpRuntimeOwnershipMode.CHECKED);
                var f=new Fixture(methods,expression,profile,dumped)){
            for(int i=0;i<9;i++){
                Memory expected=baseline.result.valueOfIndex(null,i).toValue(),actual=f.result.valueOfIndex(null,i).toValue();
                assertEquals("shift type "+i,expected.type,actual.type);assertEquals("shift value "+i,expected.toString(),actual.toString());
            }
            assertEquals("shift",f.result.valueOfIndex(null,8).toString());f.supported("bits","shl","shr");
        }
    }

    @Test public void inferredReturnKindsDoNotPromoteIntBranchToFloat()throws Throwable {
        String methods="public static function floatFirst(bool $b){if($b){return 1.5;}return 7;}"
                +"public static function intFirst(bool $b){if($b){return 7;}return 1.5;}"
                +"public static function explicitFloat(bool $b):float{if($b){return 1.5;}return 7;}";
        String expression="[NumericFlow::floatFirst(true),NumericFlow::floatFirst(false),NumericFlow::intFirst(true),NumericFlow::intFirst(false),NumericFlow::explicitFloat(false)]";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,expression,profile,dumped)){
            assertNull(f.kernel("floatfirst"));assertNull(f.kernel("intfirst"));f.supported("explicitfloat");
            Memory.Type[] expected={Memory.Type.DOUBLE,Memory.Type.INT,Memory.Type.INT,Memory.Type.DOUBLE,Memory.Type.DOUBLE};
            for(int i=0;i<expected.length;i++)assertEquals("return kind "+i,expected[i],f.result.valueOfIndex(null,i).toValue().type);
        }
    }

    @Test public void truthinessAndShortCircuitPreserveNaNZeroAndOverflowSkipping()throws Throwable {
        String methods="public static function truth(float $x):int{if($x){return 1;}return 0;}"
                +"public static function skip(bool $run,int $a):bool{return $run && ($a+1)>0;}"
                +"public static function orElse(bool $run,int $a):bool{return $run || ($a+1)>0;}";
        for(var profile:PhpOptimizationProfile.values())for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(methods,
                "[NumericFlow::truth(NAN),NumericFlow::truth(INF),NumericFlow::truth(0.0),NumericFlow::truth(-0.0),NumericFlow::skip(false,PHP_INT_MAX),NumericFlow::orElse(true,PHP_INT_MAX)]",profile,dumped)){
            assertEquals(1,f.result.valueOfIndex(null,0).toLong());assertEquals(1,f.result.valueOfIndex(null,1).toLong());
            assertEquals(0,f.result.valueOfIndex(null,2).toLong());assertEquals(0,f.result.valueOfIndex(null,3).toLong());
            assertFalse(f.result.valueOfIndex(null,4).toBoolean());assertTrue(f.result.valueOfIndex(null,5).toBoolean());f.supported("truth","skip","orelse");
        }
    }
}
