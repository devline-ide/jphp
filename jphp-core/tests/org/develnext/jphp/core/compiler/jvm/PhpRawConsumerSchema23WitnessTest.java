package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.*;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.PhpOwnershipExecution;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.Assert.*;
public class PhpRawConsumerSchema23WitnessTest extends JvmCompilerCase {
 private static final class Raw extends Attribute {
  final byte[] bytes;Raw(byte[] b){super("JPHP_Runtime_Frame_v1");bytes=b;}
  protected ByteVector write(ClassWriter w,byte[] c,int l,int s,int v){return new ByteVector().putByteArray(bytes,0,bytes.length);}
  protected Attribute read(org.objectweb.asm.ClassReader r,int o,int l,char[] c,int co,Label[] labels){byte[] b=new byte[l];for(int i=0;i<l;i++)b[i]=(byte)r.readByte(o+i);return new Raw(b);}
 }
 private static ClassNode tree(byte[] b){var n=new ClassNode();new org.objectweb.asm.ClassReader(b).accept(n,new Attribute[]{new Raw(new byte[0])},0);return n;}
 private static byte[] bytes(ClassNode n){var w=new ClassWriter(0);n.accept(w);return w.toByteArray();}
 private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dumped)throws Throwable {
  var c=new Context(source);c.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var m=new JvmCompiler(env,c).compile(false);
  if(dumped){var b=new ByteArrayOutputStream();var d=new ModuleDumper(c,env,true);d.save(m,b);m=d.load(new ByteArrayInputStream(b.toByteArray()));}
  env.getScope().loadModule(m);env.getScope().addUserModule(m);env.registerModule(m);m.include(env);return m;
 }
 @Test public void schema23NonemptyNamedGlobalAndComparisonTwinValidateIndependently()throws Throwable {
  for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
   var s=newScope();s.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);s.setDebugMode(debug);var env=new Environment(s,new ByteArrayOutputStream());
   try {
    compile(env,"function mixed_boundary(){global $a;$a=2;$b=3;return $a==$b;}return 0;",dumped);
    var fn=env.fetchFunction("mixed_boundary");var nm=fn.getNativeMethod();String name=nm.getName(),desc=PhpOwnershipExecution.descriptor(nm);byte[] original=fn.getData();
    var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());assertFalse(plan.namedGlobals().isEmpty());assertEquals(1,plan.nativeMembers().size());
    var n=tree(original);var method=n.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();
    assertEquals(23,ByteBuffer.wrap(method.attrs.stream().filter(a->a instanceof Raw).map(a->((Raw)a).bytes).findFirst().orElseThrow()).getInt());
    boolean changed=false;for(var i:method.instructions.toArray())if(i instanceof MethodInsnNode call&&call.owner.equals("php/runtime/PhpComparison")&&call.name.equals("equal")){call.name="notEqual";changed=true;break;}
    assertTrue("Actual schema23 comparison direct twin missing",changed);byte[] altered=bytes(n);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
   }finally{env.doFinal();s.getClassLoader().close();}
  }
 }
 @Test public void emptyNewSchema23TablesCannotUpgradeARealSchema21Plan()throws Throwable {
  for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
   var s=newScope();s.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);s.setDebugMode(debug);var env=new Environment(s,new ByteArrayOutputStream());
   try {
    var module=compile(env,"$a=2;$a++;return $a;",dumped);var nm=module.getNativeMethod();String name=nm.getName(),desc=PhpOwnershipExecution.descriptor(nm);byte[] original=module.getData();assertTrue(PhpRuntimeOwnership.readRuntimePlan(original,name,desc).unsupported().isEmpty());
    var n=tree(original);var method=n.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();boolean changed=false;
    for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof Raw raw){assertEquals(21,ByteBuffer.wrap(raw.bytes).getInt());byte[] b=Arrays.copyOf(raw.bytes,raw.bytes.length+16);ByteBuffer.wrap(b).putInt(23);method.attrs.set(i,new Raw(b));changed=true;}
    assertTrue(changed);byte[] altered=bytes(n);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
   }finally{env.doFinal();s.getClassLoader().close();}
  }
 }
}
