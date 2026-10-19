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

/** Mutates physical vector/CV operands, preserving the original declared import as the comparison. */
public class PhpPrimitiveCellArgumentWitnessTest extends JvmCompilerCase {
 private record Fixture(byte[] bytes,PhpOwnershipExecution.Local argument){}
 private Fixture fixture()throws Throwable {
  var scope=newScope();scope.setDebugMode(false);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
  var env=new Environment(scope,new ByteArrayOutputStream());
  try {
   var context=new Context("function borrow(&$v){$v=6;}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
   var module=new JvmCompiler(env,context).compile(false);byte[] bytes=module.getFunctions().iterator().next().getData();
   var member=member(bytes);var plan=PhpOwnershipExecution.readFramePlan(bytes,member.methodName().stringValue(),member.methodType().stringValue());
   var argument=plan.locals().stream().filter(value->value.importKind()==PhpOwnershipExecution.ImportKind.ARGUMENT).findFirst().orElseThrow();
   return new Fixture(bytes,argument);
  }finally{env.doFinal();}
 }
 private static MethodModel member(byte[] bytes){return ClassFile.of().parse(bytes).methods().stream().filter(method->method.methodName().equalsString("__invoke")).findFirst().orElseThrow();}
 private static boolean witnessed(Fixture fixture)throws Throwable {
  var member=member(fixture.bytes());var ops=member.code().orElseThrow().elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
  int at=-1;for(int index=0;index<ops.size();index++)if(ops.get(index) instanceof InvokeInstruction call&&call.owner().asInternalName().equals(PhpPrimitiveCellPlan.RUNTIME)&&call.name().equalsString("importArgument")){at=index;break;}
  assertTrue("Real default argument publisher exists",at>=0);
  var check=PhpPrimitiveCellPlan.class.getDeclaredMethod("argumentPublisher",MethodModel.class,List.class,int.class,int.class,PhpOwnershipExecution.Local.class);check.setAccessible(true);
  return (Boolean)check.invoke(null,member,ops,at,fixture.argument().jvmSlot(),fixture.argument());
 }
 private static Fixture changed(Fixture fixture,int operand)throws Throwable {
  var tree=new ClassNode();new org.objectweb.asm.ClassReader(fixture.bytes()).accept(tree,0);
  var method=tree.methods.stream().filter(value->value.name.equals("__invoke")).findFirst().orElseThrow();MethodInsnNode publisher=null;
  for(var op:method.instructions.toArray())if(op instanceof MethodInsnNode call&&call.owner.equals(PhpPrimitiveCellPlan.RUNTIME)&&call.name.equals("importArgument")){publisher=call;break;}
  assertNotNull(publisher);
  if(operand==0){AbstractInsnNode op=publisher;for(int index=0;index<7;index++)op=previous(op);assertTrue(op instanceof VarInsnNode);((VarInsnNode)op).var=fixture.argument().jvmSlot()+1;}
  else if(operand==1){AbstractInsnNode op=publisher;for(int index=0;index<5;index++)op=previous(op);method.instructions.set(op,new InsnNode(Opcodes.ICONST_0));}
  else {AbstractInsnNode load=publisher;while(load!=null&&load.getOpcode()!=Opcodes.AALOAD)load=previous(load);assertNotNull(load);method.instructions.set(previous(load),new InsnNode(Opcodes.ICONST_1));}
  var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);tree.accept(writer);return new Fixture(writer.toByteArray(),fixture.argument());
 }
 private static AbstractInsnNode previous(AbstractInsnNode op){do{op=op.getPrevious();}while(op!=null&&op.getOpcode()<0);return op;}
 @Test public void actualIncomingVectorAndCvAreWitnessed()throws Throwable{assertTrue(witnessed(fixture()));}
 @Test public void changedArgumentCvIsRejected()throws Throwable{var fixture=fixture();assertFalse(witnessed(changed(fixture,0)));}
 @Test public void changedArgumentRoleIsRejected()throws Throwable{var fixture=fixture();assertFalse(witnessed(changed(fixture,1)));}
 @Test public void changedIncomingVectorIndexIsRejected()throws Throwable{var fixture=fixture();assertFalse(witnessed(changed(fixture,2)));}
}
