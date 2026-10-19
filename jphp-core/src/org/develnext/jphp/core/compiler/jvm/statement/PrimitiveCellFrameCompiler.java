package org.develnext.jphp.core.compiler.jvm.statement;
import java.io.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.PhpPrimitiveCellPlan;
import static org.objectweb.asm.Opcodes.*;
/** Exact physical default publishers, alongside existing ownership plans. */
public final class PrimitiveCellFrameCompiler {
 private final MethodStmtCompiler method;
 private final Map<Integer,String> statics=new LinkedHashMap<>();
 private final Set<MethodInsnNode> staticGetters=Collections.newSetFromMap(new IdentityHashMap<>());
 private final Map<MethodInsnNode,Integer> staticBorrows=new IdentityHashMap<>();
 private final Map<MethodInsnNode,Integer> discards=new IdentityHashMap<>();
 private record Site(int id,String helper,int local){}
 private record Capture(int index,boolean reference,int local,String type){}
 private final Map<InsnNode,Capture> captures=new IdentityHashMap<>();
 private final Set<MethodInsnNode> closureWrappers=Collections.newSetFromMap(new IdentityHashMap<>());
 public void captureStore(InsnNode store,int index,boolean reference,int local,String type){captures.put(store,new Capture(index,reference,local,type));}
 public void closureWrapper(MethodInsnNode call){closureWrappers.add(call);}
 private record Argument(int index,boolean reference){}
 private final Map<MethodInsnNode,Argument> argumentImports=new IdentityHashMap<>();
 public void argumentImport(MethodInsnNode call,int index,boolean reference){argumentImports.put(call,new Argument(index,reference));}
 public PrimitiveCellFrameCompiler(MethodStmtCompiler method){this.method=method;}
 public void staticLocal(int id,String name){statics.put(id,name);}
 public void staticGetter(MethodInsnNode call){staticGetters.add(call);}
 public void staticBorrow(MethodInsnNode call,int local){staticBorrows.put(call,local);}
 public void discard(InsnList code){
  if(method.getCompiler().getModule().getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5)return;
  if(method.ownership().isBorrowedLifecycleDiscard(code))return;
  code.add(new InsnNode(DUP));MethodInsnNode call=new MethodInsnNode(INVOKESTATIC,PhpPrimitiveCellPlan.RUNTIME,"discard","(Ljava/lang/Object;)V",false);code.add(call);discards.put(call,-1);
 }
 /** Only this emitter's exact stack-neutral discard observer, before or after CELL metadata finalization. */
 public boolean isDiscardObserver(MethodInsnNode call){
  Integer site=discards.get(call);
  if(site==null||call.getOpcode()!=INVOKESTATIC||call.itf||!call.owner.equals(PhpPrimitiveCellPlan.RUNTIME)||!call.name.equals("discard")||next(call)==null||next(call).getOpcode()!=POP)return false;
  AbstractInsnNode cursor=previous(call);
  if(site<0)return call.desc.equals("(Ljava/lang/Object;)V")&&cursor!=null&&cursor.getOpcode()==DUP;
  if(!call.desc.equals("(Ljava/lang/Object;"+META+")V"))return false;
  if(!(cursor instanceof LdcInsnNode id)||!site.equals(id.cst))return false;
  cursor=previous(cursor);if(!(cursor instanceof LdcInsnNode descriptor)||!method.node.desc.equals(descriptor.cst))return false;
  cursor=previous(cursor);if(!(cursor instanceof LdcInsnNode name)||!method.node.name.equals(name.cst))return false;
  cursor=previous(cursor);if(!(cursor instanceof LdcInsnNode owner)||!Type.getObjectType(method.clazz.node.name).equals(owner.cst))return false;
  cursor=previous(cursor);return cursor!=null&&cursor.getOpcode()==DUP;
 }
 private static AbstractInsnNode next(AbstractInsnNode instruction){if(instruction==null)return null;do{instruction=instruction.getNext();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
 private static final String M="Lphp/runtime/Memory;",F="Lphp/runtime/PhpRuntimeOwnership$Frame;",META="Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I";
 public void finish(){
  if(method.getCompiler().getModule().getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5)return;
  List<Site> sites=new ArrayList<>();Set<AbstractInsnNode> live=reachable();
  for(AbstractInsnNode instruction:method.node.instructions.toArray()){
   if(!live.contains(instruction))continue;
   if(instruction instanceof InsnNode store&&captures.containsKey(store)){
    Capture capture=captures.get(store);InsnList operands=new InsnList();
    operands.add(new LdcInsnNode(Type.getObjectType(capture.type())));operands.add(new InsnNode(capture.reference()?ICONST_1:ICONST_0));
    int id=sites.size();operands.add(new LdcInsnNode(Type.getObjectType(method.clazz.node.name)));operands.add(new LdcInsnNode(method.node.name));operands.add(new LdcInsnNode(method.node.desc));operands.add(new LdcInsnNode(id));
    operands.add(new MethodInsnNode(INVOKESTATIC,PhpPrimitiveCellPlan.RUNTIME,"capture","(["+M+"I"+M+"Ljava/lang/Class;I"+META+")V",false));
    method.node.instructions.insertBefore(store,operands);method.node.instructions.remove(store);sites.add(new Site(id,"capture",capture.local()));continue;
   }
   if(!(instruction instanceof MethodInsnNode call))continue;
   String helper=null,descriptor=null;int local=-1;
   if(closureWrappers.contains(call)){
    helper="wrapClosure";descriptor="(Lphp/runtime/lang/IObject;"+META+")"+M;
   }else if(call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("localAddress")&&call.desc.equals("("+M+"Ljava/lang/String;"+F+")"+M)){
    AbstractInsnNode key=previous(previous(call));
    if(key instanceof LdcInsnNode literal&&literal.cst instanceof String value){
     var address=method.getLocalVariables().values().stream().filter(cv->cv.isOwnershipLocal()&&cv.name.equals(value)).findFirst().orElse(null);
     if(address!=null){helper="localAddress";local=address.index;descriptor=append(call.desc,META);}
    }
   }else if(argumentImports.containsKey(call)){
    Argument argument=argumentImports.get(call);AbstractInsnNode destination=previous(call);
    if(destination instanceof VarInsnNode cv&&cv.getOpcode()==ALOAD&&isLocal(cv.var)){
     helper="importArgument";local=cv.var;descriptor="("+M+M+"II"+META+")"+M;
     InsnList operands=new InsnList();operands.add(new LdcInsnNode(argument.index()));operands.add(new InsnNode(argument.reference()?ICONST_1:ICONST_0));method.node.instructions.insertBefore(call,operands);
    }
   }else if(call.getOpcode()==INVOKEVIRTUAL&&call.owner.equals("php/runtime/invoke/PhpCallFrame")&&call.name.equals("sendCompiler")&&call.desc.equals("("+M+"ILphp/runtime/PhpOwnershipExecution$Entry;I)V")){
    helper="sendCompiler";descriptor="(Lphp/runtime/invoke/PhpCallFrame;"+call.desc.substring(1,call.desc.indexOf(')'))+META+")V";
    AbstractInsnNode raw=previous(previous(previous(previous(call))));
    if(raw instanceof VarInsnNode load&&load.getOpcode()==ALOAD){
     AbstractInsnNode store=raw.getPrevious();while(store!=null&&!(store instanceof VarInsnNode write&&write.getOpcode()==ASTORE&&write.var==load.var))store=store.getPrevious();
     AbstractInsnNode kind=previous(store),constant=previous(kind),cv=previous(constant);
     if(kind instanceof VarInsnNode write&&write.getOpcode()==ISTORE&&cv instanceof VarInsnNode address&&address.getOpcode()==ALOAD&&isLocal(address.var))local=address.var;
    }
   }else if(discards.containsKey(call)){helper="discard";descriptor="(Ljava/lang/Object;"+META+")V";}
   else if(call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/memory/ReferenceMemory")&&call.name.equals("exportReference")&&call.desc.equals("("+M+")"+M)){
    helper="export";descriptor="("+M+META+")"+M;AbstractInsnNode read=previous(call);if(read instanceof VarInsnNode load&&load.getOpcode()==ALOAD&&isLocal(load.var))local=load.var;
   }else if(call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/Memory")&&call.desc.equals("("+M+M+")"+M)){
    if(call.name.equals("assignRefRight"))helper="assignRef";else if(call.name.equals("assignRight"))helper="assignValue";
    if(helper!=null&&previous(call) instanceof VarInsnNode read&&read.getOpcode()==ALOAD&&isLocal(read.var)){local=read.var;descriptor="("+M+M+META+")"+M;}else helper=null;
   }else if(call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("unsetLocal")){
    AbstractInsnNode env=previous(call),cv=previous(env);
    if(cv instanceof VarInsnNode read&&read.getOpcode()==ALOAD&&isLocal(read.var)){helper="unset";local=read.var;descriptor="("+F+"I"+M+"Lphp/runtime/env/Environment;"+META+")V";}
   }else if(staticGetters.contains(call)&&method.getEntity().isReturnReference()&&method.clazz.isClosure()&&call.getOpcode()==INVOKEVIRTUAL&&call.owner.equals(method.clazz.node.name)&&(call.name.equals("getStatic")||call.name.equals("getOrCreateStatic"))){
    helper=call.name;descriptor="(Lphp/runtime/lang/Closure;"+call.desc.substring(1,call.desc.indexOf(')'))+META+")"+M;
   }else if(staticBorrows.containsKey(call)&&method.getEntity().isReturnReference()&&method.clazz.isClosure()&&call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/memory/ReferenceMemory")&&call.name.equals("valueOf")&&call.desc.equals("("+M+")"+M)){
    helper="borrowStatic";local=staticBorrows.get(call);descriptor="("+M+META+")"+M;
   }else if(staticBorrows.containsKey(call)&&method.getEntity().isReturnReference()&&call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("bindStaticLocal")){
    helper="bindStatic";local=staticBorrows.get(call);descriptor=append(call.desc,META);
   }else if(call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("prepareReturnReference")){
    helper="prepareReturn";descriptor="("+F+M+"Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/lang/Closure;"+META+")"+M;
    method.node.instructions.insertBefore(call,method.clazz.isClosure()?new VarInsnNode(ALOAD,0):new InsnNode(ACONST_NULL));
   }else if(call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/PhpOwnershipClosures")){
    if(call.name.equals("cloneRuntime")){helper="cloneEval";descriptor=append(call.desc,META);}
   }
   if(helper==null)continue;int id=sites.size();if(helper.equals("discard"))discards.put(call,id);InsnList metadata=new InsnList();
   metadata.add(new LdcInsnNode(Type.getObjectType(method.clazz.node.name)));metadata.add(new LdcInsnNode(method.node.name));metadata.add(new LdcInsnNode(method.node.desc));metadata.add(new LdcInsnNode(id));
   method.node.instructions.insertBefore(call,metadata);call.setOpcode(INVOKESTATIC);call.owner=PhpPrimitiveCellPlan.RUNTIME;call.name=helper;call.desc=descriptor;call.itf=false;sites.add(new Site(id,helper,local));
  }
  if(!sites.isEmpty())method.node.attrs.add(new CellAttribute(encode(sites)));
 }
 /** Rows must describe physical publishers after ASM removes unreachable blocks. Exception edges are conservative. */
 private Set<AbstractInsnNode> reachable(){
  AbstractInsnNode[] instructions=method.node.instructions.toArray();
  Map<AbstractInsnNode,Integer> positions=new IdentityHashMap<>();for(int i=0;i<instructions.length;i++)positions.put(instructions[i],i);
  Set<AbstractInsnNode> live=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<AbstractInsnNode> pending=new ArrayDeque<>();
  if(instructions.length!=0)pending.add(instructions[0]);
  while(!pending.isEmpty()){
   AbstractInsnNode node=pending.removeFirst();if(!live.add(node))continue;int opcode=node.getOpcode();
   // These obsolete bytecodes are never certified by the reader; avoid inventing a subroutine reachability proof.
   if(opcode==JSR||opcode==RET){live.addAll(Arrays.asList(instructions));return live;}
   if(node instanceof JumpInsnNode jump){pending.add(jump.label);if(opcode!=GOTO&&node.getNext()!=null)pending.add(node.getNext());}
   else if(node instanceof TableSwitchInsnNode branch){pending.add(branch.dflt);pending.addAll(branch.labels);}
   else if(node instanceof LookupSwitchInsnNode branch){pending.add(branch.dflt);pending.addAll(branch.labels);}
   else if(opcode!=ATHROW&&(opcode<IRETURN||opcode>RETURN)&&node.getNext()!=null)pending.add(node.getNext());
   int at=positions.get(node);for(TryCatchBlockNode guard:method.node.tryCatchBlocks){
    Integer start=positions.get(guard.start),end=positions.get(guard.end);
    if(start!=null&&end!=null&&at>=start&&at<end)pending.add(guard.handler);
   }
  }
  return live;
 }
 private boolean isLocal(int id){return method.getLocalVariables().values().stream().anyMatch(local->local.index==id&&local.isOwnershipLocal());}
 private static AbstractInsnNode previous(AbstractInsnNode instruction){if(instruction==null)return null;do{instruction=instruction.getPrevious();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
 private static String append(String descriptor,String extra){int at=descriptor.indexOf(')');return descriptor.substring(0,at)+extra+descriptor.substring(at);}
 private byte[] encode(List<Site> sites){
  try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(1);out.writeUTF(method.clazz.node.name);out.writeUTF(method.node.name);out.writeUTF(method.node.desc);out.writeBoolean(method.getEntity().isReturnReference());
   var locals=method.getLocalVariables().values().stream().filter(local->local.isOwnershipLocal()).toList();out.writeInt(locals.size());for(var local:locals){out.writeInt(local.index);out.writeUTF(local.name);}
   out.writeInt(statics.size());for(var item:statics.entrySet()){out.writeInt(item.getKey());out.writeUTF(item.getValue());}
   out.writeInt(sites.size());for(Site site:sites){out.writeInt(site.id());out.writeUTF(site.helper());out.writeInt(site.local());}return bytes.toByteArray();
  }catch(IOException impossible){throw new IllegalStateException(impossible);}
 }
 private static final class CellAttribute extends Attribute{
  private final byte[] bytes;CellAttribute(byte[] bytes){super(PhpPrimitiveCellPlan.ATTRIBUTE);this.bytes=bytes;}
  @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector(bytes.length).putByteArray(bytes,0,bytes.length);}
 }
}
