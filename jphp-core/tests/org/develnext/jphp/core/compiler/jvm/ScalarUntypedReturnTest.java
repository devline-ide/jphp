package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import php.runtime.Memory;
import php.runtime.common.*;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import static org.junit.Assert.*;

/** Inference adds pure scalar kernels; dynamic/overflow/error boundaries keep the original PHP body. */
public class ScalarUntypedReturnTest extends JvmCompilerCase {
    @Test public void inferredReturnConstantsLocalsCallsAndOverflowAcrossSourceAndPhb()throws Throwable {
        String source="final class InferredScalar { "+
                "public static function add(int $a,int $b){return $a+$b;} "+
                "public static function sum(){$a=5;$b=5;return $a+$b;} "+
                "public static function literal(){return 7;} "+
                "public static function positive(int $a){return $a>0;} "+
                "public static function blend(float $a,float $b){return $a+$b;} "+
                "public static function helper(int $a){return self::add($a,2);} "+
                "public static function dynamic($a){return $a+1;} "+
                "public static function optional(int $a=1){return $a+1;} "+
                "public static function effects(int $a){echo 'effect';return $a+1;} "+
                "public static function reference(int &$a){return $a+1;} "+
                "public function instance(int $a){return $a+1;} } "+
                "$caught=false;try{InferredScalar::add('bad',1);}catch(\\TypeError $e){$caught=true;} "+
                "return [InferredScalar::add(2,3),InferredScalar::sum(),InferredScalar::literal(),InferredScalar::positive(-1),"+
                "InferredScalar::blend(1.5,2.0),InferredScalar::helper(3),InferredScalar::add(9223372036854775807,1),$caught];";
        for(boolean dumped:new boolean[]{false,true})for(var mode:PhpRuntimeOwnershipMode.values())for(var numeric:PhpOptimizationProfile.values()) {
            var scope=newScope();scope.setDebugMode(false);var env=new Environment(scope,OutputStream.nullOutputStream());
            try {
                Context context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                context.setPhpOwnershipMode(mode);context.setPhpOptimizationProfile(numeric);
                ModuleEntity module=new JvmCompiler(env,context).compile(false);
                if(dumped){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                Memory result=module.include(env);
                assertEquals(5,result.valueOfIndex(null,0).toLong());assertEquals(10,result.valueOfIndex(null,1).toLong());assertEquals(7,result.valueOfIndex(null,2).toLong());
                assertFalse(result.valueOfIndex(null,3).toBoolean());assertEquals(3.5,result.valueOfIndex(null,4).toDouble(),0);
                assertEquals(5,result.valueOfIndex(null,5).toLong());assertTrue(result.valueOfIndex(null,7).toBoolean());
                Memory overflow=result.valueOfIndex(null,6).toValue();
                assertEquals(numeric==PhpOptimizationProfile.COMPATIBLE?Memory.Type.DOUBLE:Memory.Type.INT,overflow.type);
                if(numeric==PhpOptimizationProfile.FAST_INT64_WRAP)assertEquals(Long.MIN_VALUE,overflow.toLong());
                var clazz=env.fetchClass("InferredScalar",false,PhpLanguageProfile.PHP_8_5);var code=new ClassNode();new ClassReader(clazz.getData()).accept(code,0);
                for(String name:new String[]{"add","sum","literal","positive","blend","helper","dynamic"})
                    assertEquals(name+" mode="+mode,mode!=PhpRuntimeOwnershipMode.CHECKED,code.methods.stream().anyMatch(method->method.name.startsWith("__$scalar$"+name+"$")));
                for(String name:new String[]{"optional","effects","reference","instance"})
                    assertFalse(name+" unexpectedly inferred",code.methods.stream().anyMatch(method->method.name.startsWith("__$scalar$"+name+"$")));
            }finally{env.doFinal();}
        }
    }
}
