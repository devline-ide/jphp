package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.common.PhpRuntimeOwnershipMode;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.*;
import java.util.List;

import static org.junit.Assert.*;

/** Differential PHP execution checks for control flow, including exceptional and rejected shapes. */
public class ScalarControlFlowSafetyTest extends JvmCompilerCase {
    private static final String METHODS = """
        public static function nested(int $n): int {
            $s=0; for($i=0;$i<$n;$i++) { for($j=0;$j<9;$j++) {
                if($j===2) continue; if($i===3) continue 2;
                if($i===5) break 2; $s+=$i*10+$j;
            } } return $s;
        }
        public static function whileLoop(int $n): int {
            $i=0; $s=0; while($i<$n) { ++$i; if($i===3) continue;
                if($i>12) break; if($i<6) {$s+=$i;} else {$s-=$i;}
            } return $s;
        }
        public static function branches(int $n): int {
            if($n<0) return -$n; if($n===0) return 17;
            else {$v=$n*3;} return $v+1;
        }
        public static function shift(int $a,int $b): int { return ($a<<$b)^($a>>$b); }
        public static function bitwise(int $a,int $b): int { return (~$a&2047)|($b^5); }
        public static function doubles(float $n): float {
            $s=0.0; for($i=0;$i<7;$i++) {$s+=$n; if($s>12.0) break;} return $s;
        }
        public static function truth(float $n): int {if($n) return 1; return 0;}
        public static function boolTruth(bool $n): int {if($n) return 1; return 0;}
        public static function overflow(int $a,int $n) {
            $s=$a; for($i=0;$i<$n;$i++) {$s+=1;} return $s;
        }
        public static function merged(bool $b): int {if($b){$x=3;}else{$x=9;}return $x;}
        public static function inferredFloatFirst(bool $b) {if($b)return 1.5;return 7;}
        public static function inferredIntFirst(bool $b) {if($b)return 7;return 1.5;}
        public static function unchanged(int $n): int {
            $x=7; for($i=0;$i<$n;$i++) {$x=11;} return $x;
        }
        public static function bodyLocal(int $n): int {
            for($i=0;$i<$n;$i++) {$inside=11;} return $inside;
        }
        public static function conditionalLocal(bool $b): int {if($b){$x=7;}return $x;}
        public static function kindChange(int $n) {$v=1;while($n>0){$v=1.5;--$n;}return $v;}
        public static function effect(int $n): int {
            echo 'once';$s=0;for($i=0;$i<$n;$i++){$s+=$i;}return $s;
        }
        public static function alias(int $n): int {$s=0;$r=&$s;while($n>0){$r++;$n--;}return $s;}
        public static function guard(bool $b,int $a): int {if($b){return $a+1;}return 7;}
        """;

    @Test public void sourceAndPhbMatchCheckedAcrossProfilesAndBranches() throws Throwable {
        String expression = """
            $out=[];
            foreach([0,1,3,7,15] as $n){
                $out[]=FlowSafety::nested($n);$out[]=FlowSafety::whileLoop($n);
                $out[]=FlowSafety::unchanged($n);
            }
            foreach([-7,0,8] as $n)$out[]=FlowSafety::branches($n);
            foreach([0,1,63,64,65] as $shift){$out[]=FlowSafety::shift(-17,$shift);}
            try{$out[]=FlowSafety::shift(9,-1);}catch(\\ArithmeticError $e){$out[]='negative-shift';}
            $out[]=FlowSafety::bitwise(-9,17);
            foreach([0.0,-0.0,2.5,-2.0,INF,-INF,NAN] as $d){
                $out[]=FlowSafety::doubles($d);$out[]=FlowSafety::truth($d);
            }
            $out[]=FlowSafety::boolTruth(false);$out[]=FlowSafety::boolTruth(true);
            $out[]=FlowSafety::overflow(9223372036854775806,3);
            $out[]=FlowSafety::overflow(-10,5);
            $out[]=FlowSafety::merged(false);$out[]=FlowSafety::merged(true);
            $out[]=FlowSafety::inferredFloatFirst(false);$out[]=FlowSafety::inferredFloatFirst(true);
            $out[]=FlowSafety::inferredIntFirst(false);$out[]=FlowSafety::inferredIntFirst(true);
            $out[]=FlowSafety::bodyLocal(1);$out[]=FlowSafety::conditionalLocal(true);
            $out[]=FlowSafety::kindChange(0);$out[]=FlowSafety::kindChange(2);
            $out[]=FlowSafety::effect(3);$out[]=FlowSafety::alias(5);
            $out[]=FlowSafety::guard(false,9223372036854775807);
            try{$out[]=FlowSafety::guard(true,9223372036854775807);}catch(\\TypeError $e){$out[]='overflow-return';}
            return serialize($out);
            """;
        for (var numeric : PhpOptimizationProfile.values()) for (boolean strict : new boolean[]{false,true}) {
            var checked = execute(expression,numeric,PhpRuntimeOwnershipMode.CHECKED,false,strict);
            for (var mode : List.of(PhpRuntimeOwnershipMode.AUTO,PhpRuntimeOwnershipMode.OFF)) {
                for (boolean dumped : new boolean[]{false,true}) {
                    assertEquals(mode+"/"+numeric+"/PHB="+dumped,checked,
                            execute(expression,numeric,mode,dumped,strict));
                }
            }
        }
    }

    @Test public void uninitializedAliasesEffectsAndTypeChangesStayOrdinary() throws Throwable {
        var scope=newScope();scope.setDebugMode(false);
        var env=new Environment(scope,new ByteArrayOutputStream());
        try {
            var context=new Context("final class FlowSafety {"+METHODS+"} return 0;");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            var code=new ClassNode();new ClassReader(module.getClasses().iterator().next().getData()).accept(code,0);
            for(String name:List.of("bodyLocal","conditionalLocal","kindChange","effect","alias","inferredFloatFirst","inferredIntFirst"))
                assertFalse("Unsafe optimized shape: "+name,code.methods.stream().anyMatch(m->m.name.startsWith("__$scalar$"+name.toLowerCase()+"$")));
        } finally {env.doFinal();}
    }

    private record Observation(String serialized,String output) {}
    private Observation execute(String expression,PhpOptimizationProfile numeric,PhpRuntimeOwnershipMode ownership,
                                boolean dumped,boolean strict) throws Throwable {
        var scope=newScope();scope.setDebugMode(false);var output=new ByteArrayOutputStream();
        var env=new Environment(scope,output);
        try {
            var context=new Context((strict?"declare(strict_types=1);":"")+"final class FlowSafety {"+METHODS+"}"+expression);
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            context.setPhpOptimizationProfile(numeric);context.setPhpOwnershipMode(ownership);
            var module=new JvmCompiler(env,context).compile(false);
            if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            var result=module.include(env);
            for(var clazz:module.getClasses()) {
                var diagnostics=new StringWriter();
                CheckClassAdapter.verify(new ClassReader(clazz.getData()),scope.getClassLoader(),false,new PrintWriter(diagnostics));
                assertEquals("Invalid emitted control flow","",diagnostics.toString());
            }
            return new Observation(result.toString(),output.toString(java.nio.charset.StandardCharsets.UTF_8));
        } finally {env.doFinal();}
    }
}
