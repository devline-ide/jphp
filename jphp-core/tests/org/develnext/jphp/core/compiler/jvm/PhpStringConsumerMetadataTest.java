package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.env.*;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import php.runtime.memory.LongMemory;
import php.runtime.memory.helper.ConstantMemory;
import php.runtime.reflection.CompileFunctionEntity;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;
public class PhpStringConsumerMetadataTest {
 public static class Declarations {
  @Signature(returnType="int|false") public static Memory integerOrFalse(){return Memory.FALSE;}
  @Signature(returnType="string|false") public static Memory stringOrFalse(){return Memory.FALSE;}
  @Signature(returnType="int|true") public static Memory uncheckedUnion(){return Memory.FALSE;}
  @Signature(returnType="int|false",result=@Arg(type=HintType.INT)) public static Memory conflictingScalar(){return Memory.FALSE;}
  @Signature(returnType="int|false",result=@Arg(nullable=true)) public static Memory conflictingNull(){return Memory.FALSE;}
  @Signature(value={@Arg(value="limit",type=HintType.INT,optional=@Optional(value="9223372036854775807",type=HintType.INT,constantName="PHP_INT_MAX"))}) public static Memory maximum(Memory limit){return limit;}
  @Signature(value={@Arg(value="limit",type=HintType.INT,optional=@Optional(value="0",type=HintType.INT,constantName="PHP_INT_MAX"))}) public static Memory wrongMaximum(Memory limit){return limit;}
  @Signature(value={@Arg(value="limit",type=HintType.INT,optional=@Optional(value="0",type=HintType.INT,constantName="PHP_INT_MIN"))}) public static Memory unsupportedConstant(Memory limit){return limit;}
 }
 private CompileFunctionEntity declaration(String name)throws Exception{
  var spec=new CompileFunctionSpec(name);var method=java.util.Arrays.stream(Declarations.class.getDeclaredMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();spec.addMethod(method);return new CompileFunctionEntity(new CoreExtension(),spec);
 }
 private interface Action{void run()throws Throwable;}
 private void rejected(Action a)throws Throwable{try{a.run();fail("Unsupported or conflicting native declaration was accepted");}catch(IllegalArgumentException expected){}}
 @Test public void concreteIntegerOrFalseAndExistingStringUnionRemainDistinct()throws Exception{
  assertEquals("int|false",declaration("integerOrFalse").getReturnTypeChecker().getSignature());
  assertEquals("string|false",declaration("stringOrFalse").getReturnTypeChecker().getSignature());
 }
 @Test public void unknownAndConflictingReturnDeclarationsFailClosed()throws Throwable{
  rejected(()->declaration("uncheckedUnion"));rejected(()->declaration("conflictingScalar"));rejected(()->declaration("conflictingNull"));
 }
 @Test public void symbolicMaximumIsExactAndWrongDeclarationsFailClosed()throws Throwable{
  var p=declaration("maximum").getParameters()[0];assertEquals("PHP_INT_MAX",p.getDefaultValueConstName());assertFalse(p.isNullableOrDefaultNull());assertEquals(Long.MAX_VALUE,p.getDefaultValue().toLong());
  rejected(()->declaration("wrongMaximum"));rejected(()->declaration("unsupportedConstant"));
  rejected(()->new ConstantMemory("PHP_INT_MAX",Memory.NULL));rejected(()->new ConstantMemory("PHP_INT_MAX",LongMemory.valueOf(0)));
 }
 @Test public void missingWrongAndTamperedActualMaximumCannotUseFallback()throws Throwable{
  {var scope=new CompileScope(){@Override public php.runtime.ext.support.compile.CompileConstant findCompileConstant(String name){return name.equals("PHP_INT_MAX")?null:super.findCompileConstant(name);}};var env=new Environment(scope,new ByteArrayOutputStream());
  try{var descriptor=new ConstantMemory("PHP_INT_MAX",LongMemory.valueOf(Long.MAX_VALUE));assertNull(env.findConstant("PHP_INT_MAX"));
   boolean missingRejected=false;try{descriptor.toImmutable(env,TraceInfo.UNKNOWN);}catch(Throwable expected){missingRejected=true;}assertTrue("Missing symbol used its declaration fallback",missingRejected);env.getConstants().put("php_int_max",new php.runtime.reflection.ConstantEntity("PHP_INT_MAX",LongMemory.valueOf(7),true));assertEquals(7,env.findConstant("PHP_INT_MAX").toLong());rejected(()->descriptor.toImmutable(env,TraceInfo.UNKNOWN));
  }finally{try{env.doFinal();}finally{scope.getClassLoader().close();}}
  } {var scope=new CompileScope();var env=new Environment(scope,new ByteArrayOutputStream());
  try{var descriptor=new ConstantMemory("PHP_INT_MAX",LongMemory.valueOf(Long.MAX_VALUE));assertEquals(Long.MAX_VALUE,env.findConstant("PHP_INT_MAX").toLong());assertEquals(Long.MAX_VALUE,descriptor.toImmutable(env,TraceInfo.UNKNOWN).toLong());
   ((LongMemory)descriptor.getRawValue()).value=0;final Environment actual=env;rejected(()->descriptor.toImmutable(actual,TraceInfo.UNKNOWN));
  }finally{try{env.doFinal();}finally{scope.getClassLoader().close();}}}
 }
}
