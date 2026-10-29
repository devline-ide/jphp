package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.invoke.InvokeArgumentHelper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.TypeChecker;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

public class ScalarArgumentVectorTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final Environment env;
        final MethodEntity scalar, opaque;
        Fixture() throws Throwable {
            var scope=newScope();scope.setDebugMode(false);env=new Environment(scope,new ByteArrayOutputStream());
            var context=new Context("class VectorCase {public static function scalar(int $a,int $b):int{return $a+$b;}public static function opaque(int $a,int $b):int{echo '';return $a+$b;}}");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new org.develnext.jphp.core.compiler.jvm.JvmCompiler(env,context).compile(false);
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            var type=env.fetchClass("VectorCase",false);scalar=type.findMethod("scalar");opaque=type.findMethod("opaque");
        }
        Memory[] prepare(MethodEntity target,Memory[] args) {
            return InvokeArgumentHelper.makeArguments(env,args,target,target.getParameters(),"VectorCase",target.getName(),"VectorCase",TraceInfo.UNKNOWN,false);
        }
        @Override public void close() throws Exception {try{env.doFinal();}catch(Throwable error){throw new Exception(error);}}
    }

    @Test public void onlyProvenBodiesBorrowExactIntegerArguments() throws Throwable {
        try(var f=new Fixture()) {
            Memory[] args={LongMemory.valueOf(10001),LongMemory.valueOf(20003)};
            assertTrue(f.scalar.acceptsBorrowedScalarArguments());assertFalse(f.opaque.acceptsBorrowedScalarArguments());
            assertSame(args,f.prepare(f.scalar,args));assertNotSame(args,f.prepare(f.opaque,args));
            assertSame(args[0],f.prepare(f.scalar,args)[0]);
        }
    }

    @Test public void coercionsAndReferenceCarriersRetainDefensiveCopies() throws Throwable {
        try(var f=new Fixture()) {
            Memory original=new StringMemory("10001");Memory[] args={original,LongMemory.valueOf(20003)};
            Memory[] passed=f.prepare(f.scalar,args);assertNotSame(args,passed);assertSame(original,args[0]);assertEquals(10001,passed[0].toLong());
            ReferenceMemory reference=new ReferenceMemory(LongMemory.valueOf(30007));args=new Memory[]{reference,LongMemory.valueOf(20003)};
            passed=f.prepare(f.scalar,args);assertNotSame(args,passed);assertSame(reference,args[0]);assertEquals(30007,passed[0].toLong());
        }
    }

    @Test public void overriddenMetadataAndMemoryConversionsRemainObservable() throws Throwable {
        try(var f=new Fixture()) {
            int[] checks={0};ParameterEntity parameter=f.scalar.getParameters()[0];
            parameter.setTypeChecker(new TypeChecker.Simple(HintType.INT) {
                @Override public boolean check(Environment env,Memory value,boolean nullable,String scope){checks[0]++;return super.check(env,value,nullable,scope);}
            });
            Memory[] args={LongMemory.valueOf(10001),LongMemory.valueOf(20003)};
            assertNotSame(args,f.prepare(f.scalar,args));assertTrue(checks[0]>0);
            int[] conversions={0};args=new Memory[]{new LongMemory(10001){
                @Override public Memory toImmutable(){conversions[0]++;return super.toImmutable();}
                @Override public Memory toValue(){conversions[0]++;return super.toValue();}
            },LongMemory.valueOf(20003)};
            assertNotSame(args,f.prepare(f.scalar,args));assertTrue(conversions[0]>0);
        }
    }

    @Test public void replacingNativeMethodRetiresTheProof() throws Throwable {
        try(var f=new Fixture()) {
            f.scalar.setNativeMethod(f.opaque.getNativeMethod());assertFalse(f.scalar.acceptsBorrowedScalarArguments());
            Memory[] args={LongMemory.valueOf(10001),LongMemory.valueOf(20003)};
            assertNotSame(args,f.prepare(f.scalar,args));
        }
    }
}
