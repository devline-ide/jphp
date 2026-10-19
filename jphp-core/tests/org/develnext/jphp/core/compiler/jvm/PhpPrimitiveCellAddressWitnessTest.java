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

/** Cold reads the real debug CV publisher, then changes one physical address operand or join. */
public class PhpPrimitiveCellAddressWitnessTest extends JvmCompilerCase {
 private static final String ADDRESS="(Lphp/runtime/Memory;Ljava/lang/String;Lphp/runtime/PhpRuntimeOwnership$Frame;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/Memory;";
 private record Fixture(byte[] bytes,int local,String key){}
 private Fixture fixture()throws Throwable{
  var scope=newScope();scope.setDebugMode(true);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
  var env=new Environment(scope,new ByteArrayOutputStream());
  try{
   var context=new Context("function borrow(&$v){$v=6;}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
   var module=new JvmCompiler(env,context).compile(false);byte[] bytes=module.getFunctions().iterator().next().getData();
   var method=member(bytes);var plan=PhpOwnershipExecution.readFramePlan(bytes,method.methodName().stringValue(),method.methodType().stringValue());
   var local=plan.locals().stream().filter(value->value.importKind()==PhpOwnershipExecution.ImportKind.ARGUMENT).findFirst().orElseThrow();
   return new Fixture(bytes,local.jvmSlot(),local.name());
  }finally{env.doFinal();}
 }
 private static MethodModel member(byte[] bytes){return ClassFile.of().parse(bytes).methods().stream().filter(method->method.methodName().equalsString("__invoke")).findFirst().orElseThrow();}
 private static boolean witnessed(Fixture fixture)throws Throwable{
  var method=member(fixture.bytes());var code=method.code().orElseThrow();var ops=code.elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
  int at=-1;for(int i=0;i<ops.size();i++)if(ops.get(i) instanceof InvokeInstruction call&&call.owner().asInternalName().equals(PhpPrimitiveCellPlan.RUNTIME)&&call.name().equalsString("localAddress")&&call.type().equalsString(ADDRESS)){at=i;break;}
  assertTrue("Actual default table address exists",at>=0);
  var check=PhpPrimitiveCellPlan.class.getDeclaredMethod("localAddressPublisher",MethodModel.class,CodeModel.class,List.class,int.class,int.class,String.class);check.setAccessible(true);
  return (Boolean)check.invoke(null,method,code,ops,at,fixture.local(),fixture.key());
 }
 private static AbstractInsnNode back(AbstractInsnNode op,int count){for(int i=0;i<count;i++){do{op=op.getPrevious();}while(op!=null&&op.getOpcode()<0);}return op;}
 private static Fixture changed(Fixture fixture,int operand)throws Throwable{
  var tree=new ClassNode();new org.objectweb.asm.ClassReader(fixture.bytes()).accept(tree,0);
  var method=tree.methods.stream().filter(value->value.name.equals("__invoke")).findFirst().orElseThrow();MethodInsnNode publisher=null;
  for(var op:method.instructions.toArray())if(op instanceof MethodInsnNode call&&call.owner.equals(PhpPrimitiveCellPlan.RUNTIME)&&call.name.equals("localAddress")&&call.desc.equals(ADDRESS)){publisher=call;break;}
  assertNotNull(publisher);
  if(operand==0)method.instructions.set(back(publisher,6),new LdcInsnNode("other"));
  else if(operand==1){var op=back(publisher,7);assertTrue(op instanceof VarInsnNode);((VarInsnNode)op).var=fixture.local();}
  else if(operand==2)method.instructions.set(back(publisher,11),new LdcInsnNode(fixture.local()+1));
  else if(operand==3){var op=back(publisher,8);assertTrue(op instanceof JumpInsnNode);var label=new LabelNode();method.instructions.insert(publisher,label);((JumpInsnNode)op).label=label;method.instructions.insert(label,new InsnNode(Opcodes.NOP));}
  else if(operand==4){var op=back(publisher,5);assertTrue(op instanceof VarInsnNode);((VarInsnNode)op).var=fixture.local();}
  else {var join=new LabelNode();method.instructions.insert(publisher,join);method.instructions.insertBefore(back(publisher,14),new JumpInsnNode(Opcodes.GOTO,join));}
  var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);tree.accept(writer);return new Fixture(writer.toByteArray(),fixture.local(),fixture.key());
 }
 @Test public void exactDebugAddressGuardAndPublisherAreWitnessed()throws Throwable{assertTrue(witnessed(fixture()));}
 @Test public void changedPhysicalKeyIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,0)));}
 @Test public void changedActualTableIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,1)));}
 @Test public void changedEntryCvIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,2)));}
 @Test public void changedEntryJoinIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,3)));}
 @Test public void changedDefaultFrameIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,4)));}
 @Test public void foreignBranchIntoPublisherIsRejected()throws Throwable{var f=fixture();assertFalse(witnessed(changed(f,5)));}
}
