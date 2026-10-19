package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.env.*;
import php.runtime.common.PhpLanguageProfile;
import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real fresh use-array proof; each negative changes a physical slot, source, or ingress. */
public class PhpPrimitiveCellCaptureWitnessTest extends JvmCompilerCase {
 private record Fixture(byte[] bytes,String type,Map<Integer,PhpPrimitiveCellPlan.Site> sites,Class<?> owner){}
 @SuppressWarnings("unchecked") private Fixture fixture()throws Throwable{
  var scope=newScope();scope.setDebugMode(false);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
  var env=new Environment(scope,new ByteArrayOutputStream());
  try{
   var context=new Context("$f=function &(){static $x=0;return $x;};$r=&$f();$h=function()use(&$r){return $r;};unset($r,$h);");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
   var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);Class<?> owner=module.getNativeClazz();byte[] bytes=((php.runtime.loader.RuntimeClassLoader)owner.getClassLoader()).primitiveDefinedBytes(owner);assertNotNull(bytes);var method=member(bytes);
   var headers=PhpPrimitiveCellPlan.class.getDeclaredMethod("headers",byte[].class);headers.setAccessible(true);
   Map<String,byte[]> all=(Map<String,byte[]>)headers.invoke(null,(Object)bytes);var input=new DataInputStream(new ByteArrayInputStream(all.get(method.methodName().stringValue()+method.methodType().stringValue())));
   assertEquals(1,input.readInt());input.readUTF();input.readUTF();input.readUTF();input.readBoolean();
   for(int i=0,n=input.readInt();i<n;i++){input.readInt();input.readUTF();}
   for(int i=0,n=input.readInt();i<n;i++){input.readInt();input.readUTF();}
   Map<Integer,PhpPrimitiveCellPlan.Site> sites=new HashMap<>();for(int i=0,n=input.readInt();i<n;i++){var site=new PhpPrimitiveCellPlan.Site(input.readInt(),input.readUTF(),input.readInt());sites.put(site.id(),site);}
   var ops=ops(method);int at=lastCapture(ops);assertTrue(ops.get(at-6) instanceof ConstantInstruction);
   String type=((java.lang.constant.ClassDesc)((ConstantInstruction)ops.get(at-6)).constantValue()).descriptorString();type=type.substring(1,type.length()-1);
   return new Fixture(bytes,type,sites,owner);
  }finally{env.doFinal();}
 }
 private static MethodModel member(byte[] bytes){return ClassFile.of().parse(bytes).methods().stream().filter(method->method.methodName().equalsString("__include")).findFirst().orElseThrow();}
 private static List<Instruction> ops(MethodModel method){return method.code().orElseThrow().elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();}
 private static int lastCapture(List<Instruction> ops){int at=-1;for(int i=0;i<ops.size();i++)if(ops.get(i) instanceof InvokeInstruction call&&call.owner().asInternalName().equals(PhpPrimitiveCellPlan.RUNTIME)&&call.name().equalsString("capture"))at=i;assertTrue("Actual default capture helper exists",at>=0);return at;}
 private static boolean witnessed(Fixture fixture)throws Throwable{
  var method=member(fixture.bytes());var code=method.code().orElseThrow();var ops=ops(method);int at=lastCapture(ops);
  var check=PhpPrimitiveCellPlan.class.getDeclaredMethod("captureChain",CodeModel.class,List.class,int.class,String.class,Map.class);check.setAccessible(true);
  return check.invoke(null,code,ops,at,fixture.type(),fixture.sites())!=null;
 }
 private static AbstractInsnNode back(AbstractInsnNode op,int count){for(int i=0;i<count;i++){do{op=op.getPrevious();}while(op!=null&&op.getOpcode()<0);}return op;}
 private static Fixture changed(Fixture fixture,int operand){
  var tree=new ClassNode();new org.objectweb.asm.ClassReader(fixture.bytes()).accept(tree,0);
  var method=tree.methods.stream().filter(value->value.name.equals("__include")).findFirst().orElseThrow();MethodInsnNode capture=null;
  for(var op:method.instructions.toArray())if(op instanceof MethodInsnNode call&&call.owner.equals(PhpPrimitiveCellPlan.RUNTIME)&&call.name.equals("capture"))capture=call;
  assertNotNull(capture);
  if(operand==0){var source=back(capture,12);assertTrue(source instanceof VarInsnNode);((VarInsnNode)source).var++;}
  else if(operand==1)method.instructions.set(back(capture,6),new LdcInsnNode(Type.getType(String.class)));
  else if(operand==2)method.instructions.set(back(capture,16),new InsnNode(Opcodes.ICONST_2));
  else if(operand==3)method.instructions.set(back(capture,13),new InsnNode(Opcodes.ICONST_1));
  else if(operand==4)method.instructions.set(back(capture,8),new LdcInsnNode(1000000));
  else if(operand==5)method.instructions.set(back(capture,5),new InsnNode(Opcodes.ICONST_0));
  else if(operand==6){var extra=new InsnList();extra.add(new InsnNode(Opcodes.DUP));extra.add(new VarInsnNode(Opcodes.ASTORE,123));method.instructions.insertBefore(back(capture,14),extra);}
  else if(operand==7){var target=new LabelNode();method.instructions.insertBefore(back(capture,14),target);method.instructions.insertBefore(back(capture,16),new JumpInsnNode(Opcodes.GOTO,target));}
  else if(operand==8){var start=new LabelNode();var end=new LabelNode();var handler=new LabelNode();method.instructions.insertBefore(back(capture,16),start);method.instructions.insert(capture,end);method.instructions.insertBefore(back(capture,14),handler);method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,"java/lang/Throwable"));}
  else if(operand==9){var original=back(capture,16+9);assertTrue(original instanceof TypeInsnNode&&original.getOpcode()==Opcodes.NEW);var target=new LabelNode();method.instructions.insertBefore(back(capture,16+7),target);method.instructions.insertBefore(original,new JumpInsnNode(Opcodes.GOTO,target));}
  else if(operand==10||operand==11){
   var target=new LabelNode();var otherwise=new LabelNode();method.instructions.insertBefore(back(capture,14),target);
   var count=back(capture,16);method.instructions.insertBefore(count,otherwise);
   var ingress=new InsnList();ingress.add(new InsnNode(Opcodes.ICONST_0));
   if(operand==10)ingress.add(new TableSwitchInsnNode(0,0,otherwise,target));
   else ingress.add(new LookupSwitchInsnNode(otherwise,new int[]{0},new LabelNode[]{target}));
   method.instructions.insertBefore(otherwise,ingress);
  }
  var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);tree.accept(writer);return new Fixture(writer.toByteArray(),fixture.type(),fixture.sites(),fixture.owner());
 }
 private static boolean constructorWitnessed(Fixture fixture)throws Throwable{
  var method=member(fixture.bytes());var code=method.code().orElseThrow();var ops=ops(method);int at=lastCapture(ops)+1;
  var check=PhpPrimitiveCellPlan.class.getDeclaredMethod("capturedClosureConstructor",Class.class,MethodModel.class,CodeModel.class,List.class,int.class,Map.class);check.setAccessible(true);
  return check.invoke(null,fixture.owner(),method,code,ops,at,fixture.sites())!=null;
 }
 @Test public void tableSwitchIngressToCaptureArrayIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,10)));}
 @Test public void lookupSwitchIngressToCaptureArrayIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,11)));}
 @Test public void actualCapturedConstructorIsWitnessed()throws Throwable{assertTrue(constructorWitnessed(fixture()));}
 @Test public void exceptionHandlerIngressToUseArrayIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,8)));}
 @Test public void foreignIngressToConstructorOperandsIsRejected()throws Throwable{var f=fixture();assertFalse(constructorWitnessed(changed(f,9)));}
 @Test public void actualFreshUseSlotIsWitnessed()throws Throwable{assertTrue(witnessed(fixture()));}
 @Test public void changedSourceCvIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,0)));}
 @Test public void changedCapturedClassIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,1)));}
 @Test public void changedArraySizeIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,2)));}
 @Test public void changedUseIndexIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,3)));}
 @Test public void changedExportSiteIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,4)));}
 @Test public void changedReferenceRoleIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,5)));}
 @Test public void escapedFreshArrayIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,6)));}
 @Test public void foreignIngressToCaptureArrayIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,7)));}
}
