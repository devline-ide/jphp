package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.classes.stream.MiscStream;
import php.runtime.loader.dump.*;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import static org.junit.Assert.*;

/** Real registered foreign donor bodies, exact native multi-file output and cold forged-copy rejection. */
public class PhpPropertyHookTraitDonorTest extends JvmCompilerCase {
    private static final String KIT="property-hook-trait-donor";
    private static final Map<String,String> SOURCE_PINS=Map.of(
            "ordinary-donor0-consumer0", "f8dfc199f35d3cd011e4cbac611cb93bf362bf20226eb406b000c76196c5f48c",
            "ordinary-donor0-consumer1", "6e20a7b3db5f1c8035ada421fbe639c01e300080924a81864804d934324aa13c",
            "ordinary-donor1-consumer0", "bac798e9e95ed9651e009389d5994d388b511dba6b74c5ec4b4bbe518937dd88",
            "ordinary-donor1-consumer1", "48cc0f6d7247d66463f878228a29953dd7b38f8f2842273a82568bbb121af23c",
            "linked-donor0-consumer0", "2b21fe48032a9a3728e301784ff711c8ea4c6f2381dfe49ef5c24dbd7b9db754",
            "linked-donor0-consumer1", "9733d5754dcd4429f2dea8b497d9a57be7a6487000a5e96534581800facc1477",
            "linked-donor1-consumer0", "093406a10be35a9909853405ba14fc5aee2c057dcfbab56964d7e8a8312ad01f",
            "linked-donor1-consumer1", "be19aa42779231fb79851742d52b5bd5eaaab42b794c366960ede09eab162be8");
    private static byte[] source(String id,String file)throws Exception {
        return PhpNativeTestResources.read(KIT+"/"+id+"/"+file);
    }
    private static void assertSources(String id)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        for(String name:new String[]{"donor.php","consumer.php","main.php"}) {digest.update(source(id,name));digest.update((byte)0);}
        assertEquals(SOURCE_PINS.get(id),HexFormat.of().formatHex(digest.digest()));
    }
    private ModuleEntity compile(Environment env,java.io.File file,byte[] source,boolean debug,boolean phb)throws Throwable {
        var context=new Context(new String(source,StandardCharsets.UTF_8),file);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(phb) {var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);
            module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
        return module;
    }
    private static void include(Environment env,ModuleEntity module,java.io.File file)throws Throwable {
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
        env.getModuleManager().addModule(file.toString(),module);
        module.include(env);env.getModuleManager().markIncluded(file.toString());
    }
    @Test public void foreignStrictContextAndLinkedPrivateHookBodiesMatchNativeSourceAndPhb()throws Throwable {
        for(String shape:new String[]{"ordinary","linked"})for(int donorMode:new int[]{0,1})for(int consumerMode:new int[]{0,1})
            for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
                String id=shape+"-donor"+donorMode+"-consumer"+consumerMode;assertSources(id);
                try(var fixture=PhpNativeTestResources.fixture(KIT+"/"+id,"main.php","donor.php","consumer.php","main.php")) {
                    var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                    scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                    scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                    var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();var env=new Environment(scope,out);
                    env.getDefaultBuffer().setChunkSize(0);env.defineConstant("STDERR",ObjectMemory.valueOf(new MiscStream(env,err)),true);
                    try {
                        // These are genuine prior includes; main.php keeps its real require_once sibling instructions.
                        var donorFile=fixture.sibling("donor.php").toFile();
                        var donorModule=compile(env,donorFile,source(id,"donor.php"),debug,phb);
                        ClassEntity template=null;
                        if(shape.equals("linked")) {
                            template=donorModule.getDeferredClasses().stream().filter(c->c.getName().equals("Shared")).findFirst().orElseThrow();
                            assertTrue("Conditional donor is a cold declaration template before its branch executes",template.isDeclarationTemplate());
                            assertNull("Cold conditional donor cannot acquire a physical definition",template.getNativeClass());
                        }
                        include(env,donorModule,donorFile);
                        var donor=env.fetchClass("Shared",false,PhpLanguageProfile.PHP_8_5);assertNotNull(donor);
                        assertNotNull(donor.getNativeClass());
                        assertTrue(scope.getClassLoader().matchesDefinedBytecode(donor.getNativeClass(),donor.getData()));
                        if(template!=null) {
                            assertNotSame("Execution registers the actual bound declaration, preserving the cold template",template,donor);
                            assertFalse(donor.isDeclarationTemplate());
                            assertNull("Declaration execution does not mutate the template's native identity",template.getNativeClass());
                        }
                        var consumerFile=fixture.sibling("consumer.php").toFile();
                        var consumer=compile(env,consumerFile,source(id,"consumer.php"),debug,phb);
                        include(env,consumer,consumerFile);
                        var owner=env.fetchClass("Host",false,PhpLanguageProfile.PHP_8_5);
                        for(var hook:owner.findProperty("p").getPhpHooks().declaredOrder()) {
                            assertSame(owner,hook.method().getClazz());
                            assertSame(owner.getNativeClass(),hook.method().getNativeMethod().getDeclaringClass());
                            assertFalse(owner.getMethods().containsValue(hook.method()));
                        }
                        var main=compile(env,fixture.file(),source(id,"main.php"),debug,phb);
                        include(env,main,fixture.file());env.flushAll();
                        String label=id+" debug="+debug+" PHB="+phb;
                        assertArrayEquals(label,source(id,"main.stdout"),out.toByteArray());
                        assertArrayEquals(label+" stderr",source(id,"main.stderr"),err.toByteArray());
                        assertNull(PhpRuntimeOwnership.current(env));
                    } finally {env.doFinal();}
                }
            }
    }
    @Test public void jointProfileForgeryAndBodyOrHandlerMutationRejectBeforeConsumerEffects()throws Throwable {
        String id="linked-donor1-consumer0";assertSources(id);
        try(var fixture=PhpNativeTestResources.fixture(KIT+"/"+id,"main.php","donor.php","consumer.php","main.php")) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var out=new ByteArrayOutputStream();var env=new Environment(scope,out);env.getDefaultBuffer().setChunkSize(0);
            try {
                var donorFile=fixture.sibling("donor.php").toFile();
                include(env,compile(env,donorFile,source(id,"donor.php"),false,false),donorFile);
                var module=compile(env,fixture.sibling("consumer.php").toFile(),source(id,"consumer.php"),false,false);
                var template=module.getDeferredClasses().stream().filter(type->type.getName().equals("Host")).findFirst().orElseThrow();
                assertTrue(template.isDeclarationTemplate());assertNull(template.getNativeClass());
                byte[] templateBytes=template.getData().clone();
                // Imported bodies now exist only in a runtime linked definition. Capture a genuine
                // intermediary trait copy while keeping the actual Host consumer cold and unpublished.
                var copyFile=fixture.sibling("cold-copy.php").toFile();
                include(env,compile(env,copyFile,("<?php declare(strict_types=0); trait ColdHostCopy { use Shared; }").getBytes(StandardCharsets.UTF_8),false,false),copyFile);
                var donor=env.fetchClass("Shared",false,PhpLanguageProfile.PHP_8_5);
                var owner=env.fetchClass("ColdHostCopy",false,PhpLanguageProfile.PHP_8_5);
                assertNotNull(owner);assertNotNull(owner.getNativeClass());
                var property=owner.findProperty("p");var hook=property.getPhpHooks().get();
                var loader=scope.getClassLoader();
                var snapshot=PhpTraitPropertyHookOrigin.runtimeSource(donor.findProperty("p").getPhpHooks().get(),loader,List.of(donor,owner));
                byte[] original=loader.traitHookDefinition(owner);
                String targetOwner=owner.getCompiledInternalName().replace('.','/'),targetMember=hook.method().getInternalName();
                PhpTraitPropertyHookOrigin.validateRuntimeTarget(snapshot,original,targetOwner,targetMember,loader);
                assertNull(env.fetchClass("Host",false,PhpLanguageProfile.PHP_8_5));assertEquals(0,out.size());
                for(String change:new String[]{"joint-profile","body-effect","handler","missing-origin","foreign-origin"}) {
                    ClassNode forged;
                    if(change.equals("joint-profile")||change.equals("foreign-origin")) {
                        forged=new ClassNode();new ClassReader(original).accept(forged,new Attribute[]{new RawOrigin(new byte[0])},0);
                    }else forged=PhpTraitPropertyHookOrigin.readClass(original);
                    MethodNode target=forged.methods.stream().filter(m->m.name.equals(hook.method().getInternalName())).findFirst().orElseThrow();
                    var code=new ArrayList<AbstractInsnNode>();for(AbstractInsnNode n:target.instructions)if(n.getOpcode()>=0)code.add(n);
                    if(change.equals("joint-profile")||change.equals("foreign-origin")) {
                        RawOrigin origin=(RawOrigin)target.attrs.stream().filter(a->a.type.equals(PhpTraitPropertyHookOrigin.ATTRIBUTE)).findFirst().orElseThrow();
                        var input=new DataInputStream(new ByteArrayInputStream(origin.payload));
                        int mode=input.readInt();assertEquals(2,mode);
                        String moduleName=input.readUTF(),trait=input.readUTF(),ownerName=input.readUTF(),member=input.readUTF(),hash=input.readUTF();
                        boolean strict=input.readBoolean();int sourceTrace=input.readInt(),targetTrace=input.readInt();
                        byte[] retainedTargetTuple=input.readAllBytes();assertTrue(retainedTargetTuple.length>0);
                        var bytes=new ByteArrayOutputStream();var encoded=new DataOutputStream(bytes);
                        encoded.writeInt(mode);encoded.writeUTF(moduleName);encoded.writeUTF(change.equals("foreign-origin")?"OtherTrait":trait);
                        encoded.writeUTF(ownerName);encoded.writeUTF(member);encoded.writeUTF(hash);
                        encoded.writeBoolean(change.equals("joint-profile")?false:strict);encoded.writeInt(sourceTrace);encoded.writeInt(targetTrace);
                        encoded.write(retainedTargetTuple);encoded.flush();origin.payload=bytes.toByteArray();
                        if(change.equals("joint-profile")) {
                            assertTrue(strict);assertEquals(Opcodes.ICONST_1,code.get(4).getOpcode());
                            target.instructions.set(code.get(4),new InsnNode(Opcodes.ICONST_0));
                        }
                    }else if(change.equals("body-effect"))target.instructions.insertBefore(code.get(code.size()-1),new InsnNode(Opcodes.NOP));
                    else if(change.equals("handler")) {assertFalse(target.tryCatchBlocks.isEmpty());target.tryCatchBlocks.get(0).type="java/lang/RuntimeException";}
                    else target.attrs.removeIf(a->a.type.equals(PhpTraitPropertyHookOrigin.ATTRIBUTE));
                    var writer=new ClassWriter(0);forged.accept(writer);boolean rejected=false;
                    try {PhpTraitPropertyHookOrigin.validateRuntimeTarget(snapshot,writer.toByteArray(),targetOwner,targetMember,loader);}
                    catch(LinkageError expected){rejected=true;}
                    assertTrue(change+" must reject the genuine runtime imported method before Host consumer definition",rejected);
                    assertEquals(0,out.size());assertNull(template.getNativeClass());assertTrue(template.isDeclarationTemplate());
                    assertArrayEquals(templateBytes,template.getData());
                    assertNull(env.fetchClass("Host",false,PhpLanguageProfile.PHP_8_5));
                    assertNull(PhpRuntimeOwnership.current(env));
                }
            } finally {env.doFinal();}
        }
    }
    private static final class RawOrigin extends Attribute {
        private byte[] payload;
        RawOrigin(byte[] bytes){super(PhpTraitPropertyHookOrigin.ATTRIBUTE);payload=bytes;}
        @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals) {
            return new ByteVector().putByteArray(payload,0,payload.length);
        }
        @Override protected Attribute read(ClassReader reader,int offset,int length,char[] chars,int codeOffset,Label[] labels) {
            byte[] bytes=new byte[length];for(int at=0;at<length;at++)bytes[at]=(byte)reader.readByte(offset+at);return new RawOrigin(bytes);
        }
    }
}
