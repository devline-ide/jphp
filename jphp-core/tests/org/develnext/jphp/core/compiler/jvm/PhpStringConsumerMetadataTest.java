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
  @Signature(returnType="array|false") public static Memory arrayOrFalse(){return Memory.FALSE;}
  @Signature(returnType="array|string|null") public static Memory arrayStringOrNull(){return Memory.NULL;}
  @Signature(returnType="array|string|false") public static Memory uncheckedArrayStringUnion(){return Memory.FALSE;}
  @Signature(returnType="array|true") public static Memory uncheckedArrayUnion(){return Memory.FALSE;}
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
 @Test public void concreteArrayOrFalseAcceptsOnlyItsTwoArms()throws Exception{
  var function=declaration("arrayOrFalse");var checker=function.getReturnTypeChecker();
  assertEquals("array|false",checker.getSignature());assertFalse(function.isReturnTypeNullable());
  assertTrue(checker.check(null,new php.runtime.memory.ArrayMemory(),false,null));assertTrue(checker.check(null,Memory.FALSE,false,null));
  assertFalse(checker.check(null,php.runtime.memory.StringMemory.valueOf(""),false,null));assertFalse(checker.check(null,Memory.TRUE,false,null));
  assertFalse(checker.check(null,LongMemory.valueOf(0),false,null));assertFalse(checker.check(null,Memory.NULL,false,null));
 }
 @Test public void concreteArrayStringOrNullAcceptsOnlyItsThreeArms()throws Exception{
  var function=declaration("arrayStringOrNull");var checker=function.getReturnTypeChecker();
  assertEquals("array|string|null",checker.getSignature());assertTrue(function.isReturnTypeNullable());
  assertTrue(checker.check(null,new php.runtime.memory.ArrayMemory(),false,null));assertTrue(checker.check(null,php.runtime.memory.StringMemory.valueOf(""),false,null));
  assertTrue(checker.check(null,Memory.NULL,false,null));assertFalse(checker.check(null,Memory.FALSE,false,null));assertFalse(checker.check(null,LongMemory.valueOf(0),false,null));
 }
 @Test public void unknownAndConflictingReturnDeclarationsFailClosed()throws Throwable{
  rejected(()->declaration("uncheckedUnion"));rejected(()->declaration("uncheckedArrayUnion"));rejected(()->declaration("uncheckedArrayStringUnion"));rejected(()->declaration("conflictingScalar"));rejected(()->declaration("conflictingNull"));
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

 /** Java native declarations are compared through PHP ReflectionParameter in SOURCE and real PHB. */
 public enum MetadataRange { VALUE }
 public static class UntypedDeclarations extends php.runtime.ext.support.compile.FunctionsContainer {
  @Signature(@Arg(value="value",reference=true,optional=@Optional("NULL")))
  public static Memory qa_meta_untyped(@php.runtime.annotation.Runtime.Reference Memory value){return value;}
  @Signature(@Arg(value="value",reference=true,optional=@Optional("NULL")))
  public static Memory qa_meta_untyped(){return Memory.NULL;}
  @Signature(@Arg(value="value",type=HintType.MIXED,optional=@Optional("NULL")))
  public static Memory qa_meta_mixed(Memory value){return value;}
  @Signature(@Arg(value="value",type=HintType.MIXED,optional=@Optional("NULL")))
  public static Memory qa_meta_mixed(){return Memory.NULL;}
  @Signature(@Arg(value="value",typeClass="stdClass",nullable=true,optional=@Optional("NULL")))
  public static Memory qa_meta_class(Memory value){return value;}
  @Signature(@Arg(value="value",typeClass="stdClass",nullable=true,optional=@Optional("NULL")))
  public static Memory qa_meta_class(){return Memory.NULL;}
  @Signature(@Arg(value="value",typeDeclaration="array|string"))
  public static Memory qa_meta_union(Memory value){return value;}
  @Signature(@Arg(value="value",type=HintType.INT,optional=@Optional(value="7",type=HintType.INT)))
  public static Memory qa_meta_default(Memory value){return value;}
  @Signature(@Arg(value="value",type=HintType.INT,optional=@Optional(value="7",type=HintType.INT)))
  public static Memory qa_meta_default(){return LongMemory.valueOf(7);}
  @Signature(@Arg(value="value",typeEnum=MetadataRange.class))
  public static Memory qa_meta_enum_hint(Memory value){return value;}
 }
 private static class UntypedMetadataExtension extends php.runtime.ext.support.Extension {
  @Override public String getName(){return "qa-native-untyped-metadata";}
  @Override public Status getStatus(){return Status.STABLE;}
  @Override public void onRegister(CompileScope scope){registerFunctions(new UntypedDeclarations());}
 }
 private static byte[] untypedResource(String name)throws Exception {
  try(var input=PhpStringConsumerMetadataTest.class.getResourceAsStream("/resources/native-oracle/native-untyped-parameters/"+name)){
   assertNotNull("Missing portable native parameter fixture "+name,input);return input.readAllBytes();
  }
 }
 @Test public void untypedNativeParameterReflectionIsNullWhileExplicitDeclarationsSurviveSourceAndDump()throws Throwable {
  byte[] expected=untypedResource("metadata.stdout");assertEquals(5,new String(expected,java.nio.charset.StandardCharsets.UTF_8).lines().count());assertEquals(0,untypedResource("metadata.stderr").length);
  String nativeProgram=new String(untypedResource("metadata.php"),java.nio.charset.StandardCharsets.UTF_8);
  String begin="// NATIVE_DECLARATIONS_BEGIN\n",end="// NATIVE_DECLARATIONS_END\n";
  assertEquals(1,nativeProgram.split(java.util.regex.Pattern.quote(begin),-1).length-1);assertEquals(1,nativeProgram.split(java.util.regex.Pattern.quote(end),-1).length-1);
  // The native oracle declares PHP functions; this execution supplies corresponding real FunctionsContainer entities.
  String sourceText=nativeProgram.substring(0,nativeProgram.indexOf(begin))+nativeProgram.substring(nativeProgram.indexOf(end)+end.length());
  // Context(String) compiles a PHP fragment; preserve the native file oracle and remove only its opening tag.
  assertTrue("Native metadata fixture must begin with its exact PHP opening tag",sourceText.startsWith("<?php\n"));
  sourceText=sourceText.substring("<?php\n".length());
  for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
   var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(php.runtime.common.PhpLanguageProfile.JPHP_LEGACY);
   scope.registerExtension(new CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());scope.registerExtension(new UntypedMetadataExtension());
   var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
   try{
    var context=new Context(sourceText);context.setPhpLanguageProfile(php.runtime.common.PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
    if(phb){var bytes=new ByteArrayOutputStream();var dumper=new php.runtime.loader.dump.ModuleDumper(context,env,debug);dumper.save(module,bytes);module=dumper.load(new java.io.ByteArrayInputStream(bytes.toByteArray()));}
    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
    assertArrayEquals("Native parameter metadata debug="+debug+" PHB="+phb,expected,output.toByteArray());
    assertEquals(php.runtime.common.PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
    for(String name:new String[]{"qa_meta_untyped","qa_meta_mixed","qa_meta_class","qa_meta_union","qa_meta_default","qa_meta_enum_hint"})assertTrue(env.fetchFunction(name) instanceof CompileFunctionEntity);
    assertNull(env.fetchFunction("qa_meta_untyped").getParameters()[0].getTypeChecker());
    assertEquals(HintType.MIXED,env.fetchFunction("qa_meta_mixed").getParameters()[0].getType());
    assertEquals("stdClass",env.fetchFunction("qa_meta_class").getParameters()[0].getTypeClass());
    assertEquals("array|string",env.fetchFunction("qa_meta_union").getParameters()[0].getTypeChecker().getSignature());
    assertEquals(7,env.fetchFunction("qa_meta_default").getParameters()[0].getDefaultValue().toLong());
    // The existing Java enum-range hint is retained; it is not presented as a native PHP enum type.
    assertEquals(MetadataRange.class,env.fetchFunction("qa_meta_enum_hint").getParameters()[0].getTypeEnum());
    System.out.println("NATIVE_PARAMETER_METADATA5_BYTES debug="+debug+" PHB="+phb+" "+java.util.Base64.getEncoder().encodeToString(output.toByteArray()));
   }catch(Throwable failure){primary=failure;throw failure;}finally{
    try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;if(primary!=cleanup)primary.addSuppressed(cleanup);}finally{scope.getClassLoader().close();}
   }
  }
 }
}
