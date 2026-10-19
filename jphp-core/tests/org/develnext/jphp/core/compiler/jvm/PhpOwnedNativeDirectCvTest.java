package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Exact generated CV SEND + physical native BODY gates; the host admits only the actual selected methods. */
public class PhpOwnedNativeDirectCvTest {
    private static final String DECLARATIONS="declare(strict_types=1);"
            +"class OwnedJoinSeparator {function __toString(){global $items;$items['b']='X';return \"\\xff\";}}"
            +"class OwnedJoinElement {function __toString(){global $items;$items['b']='X';return \"\\xfe\";}}"
            +"function owned_plain(){ $a=[\"\\xff\",\"\\0\",\"\\xfe\"];return implode('|',$a);}"
            +"function owned_direct(){global $items;$items=['a'=>'A','b'=>'B'];$sep=new OwnedJoinSeparator();return implode($sep,$items);}"
            +"function owned_element(){global $items;$items=['a'=>new OwnedJoinElement(),'b'=>'B'];return join('|',$items);}"
            +"function owned_bad(){ $a=['A'];return implode(17,$a);}return 0;";

    private interface Check {void run(Fixture f)throws Throwable;}
    private static final class Fixture implements AutoCloseable {
        final CompileScope scope;final Environment env;final ModuleEntity module;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();final PhpRuntimeOwnership.Authority authority;
        final PhpOwnershipDomain.Execution execution;int borrowed,ownedArrays;
        Fixture(boolean debug,boolean dumped,boolean strict)throws Throwable {
            scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
            var context=new Context(strict?DECLARATIONS:DECLARATIONS.replace("declare(strict_types=1);","declare(strict_types=0);"));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var compiled=new JvmCompiler(env,context).compile(false);
            if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(compiled,bytes);compiled=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=compiled;scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            for(var clazz:module.getClasses())clazz.initializeRuntimeState(env);
            authority=new PhpRuntimeOwnership.Authority(env,domain);execution=authority.enter();
            for(String name:new String[]{"directImplode","nativeJoin"})for(int arity:new int[]{1,2}) {
                Class<?>[] types=arity==1?new Class<?>[]{Environment.class,TraceInfo.class,Memory.class}
                        :new Class<?>[]{Environment.class,TraceInfo.class,Memory.class,Memory.class};
                var exact=org.develnext.jphp.zend.ext.standard.StringFunctions.class.getDeclaredMethod(name,types);
                authority.registerNative(exact,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                    public void validateMember(java.lang.reflect.Method member){assertEquals(exact,member);}
                    public void validateArguments(List<PhpRuntimeOwnership.Value> values){
                        assertEquals(arity,values.size());
                        for(var value:values) {
                            if(value.role()==PhpRuntimeOwnership.Role.BORROW_ADDRESS){
                                assertTrue(value.raw() instanceof ReferenceMemory);assertNull(value.node());borrowed++;
                            } else if(value.raw() instanceof ArrayMemory){
                                assertEquals(PhpRuntimeOwnership.Role.VALUE,value.role());assertNotNull(value.node());ownedArrays++;
                            }
                        }
                    }
                });
            }
        }
        FunctionEntity admit(String name)throws Throwable {
            var fn=env.fetchFunction(name);authority.registerFunction(fn,fn.getData(),(plan,member,bytes)->assertEquals(PhpOwnershipExecution.EntryKind.FUNCTION,plan.entryKind()));return fn;
        }
        void admitMagic(String name)throws Throwable {
            var clazz=env.fetchClass(name);var method=clazz.methodMagicToString;
            authority.registerMethod(method,clazz.getData(),(plan,member,bytes)->assertEquals(PhpOwnershipExecution.EntryKind.METHOD,plan.entryKind()));
        }
        void output(String name,String hex)throws Throwable {
            var fn=admit(name);try(var returned=authority.invoke(fn,TraceInfo.UNKNOWN)){
                assertArrayEquals(HexFormat.of().parseHex(hex),returned.raw().getBinaryBytes(StandardCharsets.UTF_8));
            }
            assertNull(authority.currentFrame());assertNull(PhpRuntimeOwnership.currentNativeBody(env));
        }
        public void close()throws Exception {
            authority.close();execution.close();try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}finally{scope.getClassLoader().close();}
        }
    }
    private void modes(Check check)throws Throwable {modes(false,check);}
    private void modes(boolean strict,Check check)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})try(var f=new Fixture(debug,dumped,strict)){check.run(f);}
    }
    @Test public void physicalValueCvArrayAndRawBytesWorkWithoutExportingCell()throws Throwable {
        modes(f->{f.output("owned_plain","ff7c007cfe");assertEquals(0,f.domain.graph().liveNodes());});
    }
    @Test public void separatorCallbackReadsLiveGlobalCvArrayAfterConversion()throws Throwable {
        modes(f->{f.admitMagic("OwnedJoinSeparator");f.output("owned_direct","41ff58");assertTrue(f.borrowed>0);});
    }
    @Test public void elementCallbackRetainsParsedArraySnapshotWhileOriginalCvChanges()throws Throwable {
        modes(f->{f.admitMagic("OwnedJoinElement");f.output("owned_element","fe7c42");assertTrue(f.ownedArrays>0);});
    }
    @Test public void nativeTypeFailureCleansPacketAndSubsequentDirectCallStillWorks()throws Throwable {
        modes(true,f->{var bad=f.admit("owned_bad");boolean rejected=false;
            try(var ignored=f.authority.invoke(bad,TraceInfo.UNKNOWN)){fail("Strict direct scalar separator was accepted");}
            catch(php.runtime.lang.exception.BaseTypeError failure){
                assertEquals("implode(): Argument #1 ($separator) must be of type string, int given",failure.getMessage(f.env).toString());
                rejected=true;
            }
            assertTrue(rejected);assertNull(f.authority.currentFrame());assertNull(PhpRuntimeOwnership.currentNativeBody(f.env));
            f.output("owned_plain","ff7c007cfe");assertEquals(0,f.domain.graph().liveNodes());});
    }
    @Test public void changedMetadataLocalCannotBorrowAnotherValidPhysicalCvSlot()throws Throwable {
        modes(f->{var fn=f.env.fetchFunction("owned_plain");byte[] bytes=fn.getData().clone();
            byte[] prefix="nativeDirectCvSend-fast-wrapped:".getBytes(StandardCharsets.US_ASCII);int at=-1;
            for(int i=0;i<=bytes.length-prefix.length;i++){boolean same=true;for(int j=0;j<prefix.length;j++)if(bytes[i+j]!=prefix[j])same=false;if(same){at=i+prefix.length;break;}}
            assertTrue("Actual generated CV SEND proof missing",at>=0);int colons=0;while(colons<2){if(bytes[at++]==':')colons++;}
            assertTrue(bytes[at]>='0'&&bytes[at]<='9');bytes[at]=(byte)(bytes[at]=='9'?'8':'9');
            boolean rejected=false;try{PhpRuntimeOwnership.readRuntimePlan(bytes,fn.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(fn.getNativeMethod()));}
            catch(IOException expected){rejected=true;}assertTrue("Forged local index borrowed another physical CV",rejected);
            f.output("owned_plain","ff7c007cfe");});
    }

    private static byte[] changedBoundaryOperand(byte[] original,String helper,int backwards,boolean literal) {
        var tree=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(original).accept(tree,0);
        boolean changed=false;
        for(var method:tree.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                &&(call.owner.equals("php/runtime/PhpRuntimeOwnership")||helper.equals("normalizeExplicitReturn")&&call.owner.equals("php/runtime/invoke/InvokeHelper"))&&call.name.equals(helper)) {
            var operand=instruction;for(int i=0;i<backwards;i++){do{operand=operand.getPrevious();}while(operand!=null&&operand.getOpcode()<0);}
            assertNotNull(operand);
            if(literal) {
                int value;
                if(operand instanceof org.objectweb.asm.tree.LdcInsnNode constant){assertTrue(constant.cst instanceof Integer);value=(Integer)constant.cst;}
                else if(operand instanceof org.objectweb.asm.tree.IntInsnNode constant)value=constant.operand;
                else {assertTrue(operand.getOpcode()>=org.objectweb.asm.Opcodes.ICONST_M1&&operand.getOpcode()<=org.objectweb.asm.Opcodes.ICONST_5);value=operand.getOpcode()-org.objectweb.asm.Opcodes.ICONST_0;}
                method.instructions.set(operand,new org.objectweb.asm.tree.LdcInsnNode(value+1));
            } else {
                assertTrue(operand instanceof org.objectweb.asm.tree.VarInsnNode);var local=(org.objectweb.asm.tree.VarInsnNode)operand;
                assertEquals(org.objectweb.asm.Opcodes.ALOAD,local.getOpcode());local.var++;
            }
            changed=true;break;
        }
        assertTrue("Physical boundary helper missing: "+helper,changed);
        var writer=new org.objectweb.asm.ClassWriter(0);tree.accept(writer);return writer.toByteArray();
    }
    private static void rejectedBoundary(MethodEntity method,byte[] bytes)throws Exception {
        boolean rejected=false;
        try{PhpRuntimeOwnership.readRuntimePlan(bytes,method.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(method.getNativeMethod()));}
        catch(IOException expected){rejected=true;}
        assertTrue("Modified physical callback boundary was admitted",rejected);
    }
    @Test public void namedGlobalEnvironmentOperandCannotUseAnotherPhysicalLocal()throws Throwable {
        modes(f->{var c=f.env.fetchClass("OwnedJoinSeparator");var method=c.methodMagicToString;
            rejectedBoundary(method,changedBoundaryOperand(c.getData(),"namedGlobal",1,false));
            f.admitMagic("OwnedJoinSeparator");f.output("owned_direct","41ff58");});
    }
    @Test public void namedGlobalIdCannotImportAnotherDeclaredAddress()throws Throwable {
        modes(f->{var c=f.env.fetchClass("OwnedJoinSeparator");var method=c.methodMagicToString;
            rejectedBoundary(method,changedBoundaryOperand(c.getData(),"namedGlobal",3,true));
            f.admitMagic("OwnedJoinSeparator");f.output("owned_direct","41ff58");});
    }
    @Test public void stringEntryIdCannotConsumeAnotherProducer()throws Throwable {
        modes(f->{var c=f.env.fetchClass("OwnedJoinElement");var method=c.methodMagicToString;
            rejectedBoundary(method,changedBoundaryOperand(c.getData(),"entryString",3,true));
            f.admitMagic("OwnedJoinElement");f.output("owned_element","fe7c42");});
    }
    @Test public void returnNormalizerCannotReadAnotherValueLocal()throws Throwable {
        modes(f->{var c=f.env.fetchClass("OwnedJoinElement");var method=c.methodMagicToString;
            rejectedBoundary(method,changedBoundaryOperand(c.getData(),"normalizeExplicitReturn",1,false));
            f.admitMagic("OwnedJoinElement");f.output("owned_element","fe7c42");});
    }

    @Test public void oversizedRawLiteralEntryKeepsOrdinaryChunkedCompilerRoute()throws Throwable {
        byte[] expected=new byte[17000];Arrays.fill(expected,(byte)255);
        String source="function ordinary_large(){ $a=[];$a['b']=\""+"\\xff".repeat(17000)+"\";return $a['b'];}return 0;";
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            CompileScope scope=new CompileScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            scope.registerExtension(new CoreExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
            Environment env=new Environment(scope);
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var compiler=new org.develnext.jphp.core.compiler.jvm.JvmCompiler(env,context);
                ModuleEntity module=compiler.compile(false);
                if(dumped){var bytes=new ByteArrayOutputStream();new ModuleDumper(context,env,true).save(module,bytes);module=new ModuleDumper(context,env,true).load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                Memory result=env.fetchFunction("ordinary_large").invoke(env,TraceInfo.UNKNOWN,new Memory[0]);
                assertArrayEquals(expected,result.getBinaryBytes(StandardCharsets.UTF_8));
            } finally {env.doFinal();scope.getClassLoader().close();}
        }
    }
}
