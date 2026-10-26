package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.classes.stream.MiscStream;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.PhpTraitPropertyHookOrigin;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import static org.junit.Assert.*;

/** Genuine prior includes and runtime-composed nested trait donors; native output remains byte exact. */
public class PhpPropertyHookNestedRuntimeTraitTest extends JvmCompilerCase {
    /** Semantic positions are stable; retain the actual nodes so corruption changes the physical body. */
    private static List<AbstractInsnNode> hookPrefix(MethodNode method) {
        var code=new ArrayList<AbstractInsnNode>();for(AbstractInsnNode node:method.instructions)if(node.getOpcode()>=0)code.add(node);
        if(code.size()>6 && code.get(6) instanceof MethodInsnNode enter && enter.owner.equals("php/runtime/PhpPropertyWriteContext")
                && enter.name.equals("enter") && enter.desc.equals("(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;ZI)Lphp/runtime/PhpPropertyWriteContext$Frame;")) {
            assertTrue(code.get(5) instanceof LdcInsnNode literal && literal.cst instanceof Integer id && (id==0||id==1));
            code.remove(5);
        }
        return code;
    }
    private static LdcInsnNode numericProfileLiteral(MethodNode method) {
        var code=new ArrayList<AbstractInsnNode>();for(AbstractInsnNode node:method.instructions)if(node.getOpcode()>=0)code.add(node);
        assertTrue("Current ABI emits an explicit numeric profile",code.get(5) instanceof LdcInsnNode && code.get(6) instanceof MethodInsnNode enter
                && enter.owner.equals("php/runtime/PhpPropertyWriteContext") && enter.name.equals("enter") && enter.desc.contains(";ZI)"));
        return (LdcInsnNode)code.get(5);
    }
    private static final String KIT="property-hook-nested-runtime";
    private static final String[] FILES={"legacy.php","leaf.php","middle.php","consumer.php","main.php"};
    private static byte[] source(String id,String name)throws Exception {return PhpNativeTestResources.read(KIT+"/"+id+"/"+name);}
    private ModuleEntity compile(Environment env,java.io.File file,byte[] source,boolean debug,boolean phb,PhpLanguageProfile profile)throws Throwable {
        Context context=new Context(new String(source,StandardCharsets.UTF_8),file);context.setPhpLanguageProfile(profile);
        var module=new JvmCompiler(env,context).compile(false);
        if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
        assertEquals(profile,module.getPhpLanguageProfile());return module;
    }
    private static void include(Environment env,ModuleEntity module,java.io.File file)throws Throwable {
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
        env.getModuleManager().addModule(file.toString(),module);module.include(env);env.getModuleManager().markIncluded(file.toString());
    }
    @Test public void nestedAlreadyBoundTraitChainsKeepNativeStrictWarningsReceiverReferenceAndProfilesSourcePhbDebug()throws Throwable {
        JsonArray rows=new Gson().fromJson(new String(PhpNativeTestResources.read(KIT+"/controls.json"),StandardCharsets.UTF_8),JsonArray.class);
        assertEquals(16,rows.size());List<Throwable> failures=new ArrayList<>();int variants=0;
        for(JsonElement element:rows)for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            variants++;JsonObject row=element.getAsJsonObject();String id=row.get("id").getAsString(),label=id+" debug="+debug+" PHB="+phb;
            try(var fixture=PhpNativeTestResources.fixture(KIT+"/"+id,"main.php",FILES)) {
                var digest=MessageDigest.getInstance("SHA-256");for(String file:FILES){digest.update(source(id,file));digest.update((byte)0);}
                assertEquals(row.get("sourceDigest").getAsString(),HexFormat.of().formatHex(digest.digest()));
                var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.setDebugMode(debug);
                scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();var env=new Environment(scope,out);env.getDefaultBuffer().setChunkSize(0);
                env.defineConstant("STDERR",ObjectMemory.valueOf(new MiscStream(env,err)),true);
                try {
                    var templates=new LinkedHashMap<String,ClassEntity>();
                    for(String name:new String[]{"legacy.php","leaf.php","middle.php","consumer.php"}) {
                        var file=fixture.sibling(name).toFile();PhpLanguageProfile profile=name.equals("legacy.php")?PhpLanguageProfile.JPHP_LEGACY:PhpLanguageProfile.PHP_8_5;
                        var module=compile(env,file,source(id,name),debug,phb,profile);
                        if(name.equals("leaf.php") || name.equals("middle.php")) {
                            String traitName=name.equals("leaf.php")?"Leaf":"Middle";
                            var template=module.getDeferredClasses().stream().filter(value->value.getName().equals(traitName)).findFirst().orElseThrow();
                            assertTrue(label+" conditional donor must remain a cold template before include",template.isDeclarationTemplate());
                            assertNull(label+" unexecuted donor acquired a physical class",template.getNativeClass());
                            templates.put(traitName,template);
                        }
                        include(env,module,file);
                        assertEquals(label+" global compile scope changed",PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
                    }
                    ClassEntity leaf=env.fetchClass("Leaf",false,PhpLanguageProfile.PHP_8_5),middle=env.fetchClass("Middle",false,PhpLanguageProfile.PHP_8_5),host=env.fetchClass("Host",false,PhpLanguageProfile.PHP_8_5);
                    assertNotNull(leaf);assertNotNull(middle);assertNotNull(host);
                    for(ClassEntity trait:List.of(leaf,middle)) {
                        var template=templates.get(trait.getName());assertNotNull(template);
                        assertNotSame(label+" include did not bind the actual conditional declaration",template,trait);
                        assertFalse(label,trait.isDeclarationTemplate());
                        assertNull(label+" include mutated the cold template's native identity",template.getNativeClass());
                        assertEquals(label,template.getCompiledInternalName(),trait.getCompiledInternalName());
                        assertEquals(label,template.getDeclarationId(),trait.getDeclarationId());
                        assertEquals(label,PhpLanguageProfile.PHP_8_5,trait.getModule().getPhpLanguageProfile());
                        assertNotNull(trait.getNativeClass());assertTrue(label,scope.getClassLoader().matchesDefinedBytecode(trait.getNativeClass(),trait.getData()));
                        if(trait==leaf) {
                            assertNull(label+" flat Leaf unexpectedly acquired trait composition",leaf.getClassComposition());
                            assertEquals(label+" flat Leaf retains its declared JVM class name",template.getCompiledInternalName().replace('/','.'),leaf.getNativeClass().getName());
                        } else {
                            assertNotNull(label+" Middle must use actual runtime trait composition",middle.getClassComposition());
                            assertNotEquals(label+" actual composition must use its loader-linked physical identity",middle.getCompiledInternalName().replace('/','.'),middle.getNativeClass().getName());
                            byte[] physical=scope.getClassLoader().traitHookDefinition(middle);
                            assertEquals(label,middle.getNativeClass().getName().replace('.','/'),PhpTraitPropertyHookOrigin.readClass(physical).name);
                            for(String property:new String[]{"p","q"})for(var hook:middle.findProperty(property).getPhpHooks().declaredOrder()) {
                                var donor=leaf.findProperty(property).getPhpHooks().declaredOrder().stream().filter(value->value.kind()==hook.kind()).findFirst().orElseThrow();
                                var snapshot=PhpTraitPropertyHookOrigin.runtimeSource(donor,scope.getClassLoader(),List.of(leaf,middle));
                                // The public verifier requires exact v2 runtime mode, donor hash/trace and complete source-target pair.
                                PhpTraitPropertyHookOrigin.validateRuntimeTarget(snapshot,physical,middle.getCompiledInternalName(),hook.method().getInternalName(),scope.getClassLoader());
                            }
                            assertNull(PhpRuntimeOwnership.current(env));
                        }
                        for(var hook:trait.findProperty("p").getPhpHooks().declaredOrder()) {
                            assertSame(trait,hook.method().getClazz());assertSame(trait.getNativeClass(),hook.method().getNativeMethod().getDeclaringClass());
                            assertFalse(trait.getMethods().containsValue(hook.method()));
                        }
                    }
                    for(String property:new String[]{"p","q"})for(var hook:host.findProperty(property).getPhpHooks().declaredOrder()) {
                        assertSame(host,hook.method().getClazz());assertSame(host.getNativeClass(),hook.method().getNativeMethod().getDeclaringClass());assertFalse(host.getMethods().containsValue(hook.method()));
                    }
                    assertEquals(PhpLanguageProfile.JPHP_LEGACY,env.fetchClass("LegacyHelper",false,PhpLanguageProfile.JPHP_LEGACY).getModule().getPhpLanguageProfile());
                    include(env,compile(env,fixture.file(),source(id,"main.php"),debug,phb,PhpLanguageProfile.PHP_8_5),fixture.file());env.flushAll();
                    assertArrayEquals(label,source(id,"main.stdout"),out.toByteArray());assertArrayEquals(label+" stderr",source(id,"main.stderr"),err.toByteArray());
                    assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());assertNull(PhpRuntimeOwnership.current(env));
                }finally{env.doFinal();}
            }catch(Throwable failed){failures.add(new AssertionError(label,failed));}
        }
        assertEquals(64,variants);
        if(!failures.isEmpty()){var all=new AssertionError(failures.size()+" native nested runtime-trait variants failed");failures.forEach(all::addSuppressed);throw all;}
    }

    @Test public void runtimeCopiedHookBodyHeaderHandlerAndOriginCorruptionRejectExactPair()throws Throwable {
        withRuntimeMiddle((env,leaf,middle) -> {
            var source=PhpTraitPropertyHookOrigin.runtimeSource(leaf.findProperty("p").getPhpHooks().get(),env.getScope().getClassLoader(),List.of(leaf,middle));
            byte[] actual=env.getScope().getClassLoader().traitHookDefinition(middle);
            String owner=middle.getCompiledInternalName().replace('.','/'),member=middle.findProperty("p").getPhpHooks().get().method().getInternalName();
            PhpTraitPropertyHookOrigin.validateRuntimeTarget(source,actual,owner,member,env.getScope().getClassLoader());
            // JDK ClassFile removes consecutive identical line entries; they describe the same line map.
            ClassNode redundant=PhpTraitPropertyHookOrigin.readClass(actual);
            MethodNode redundantMethod=redundant.methods.stream().filter(value->value.name.equals(member)).findFirst().orElseThrow();
            var redundantCode=hookPrefix(redundantMethod);var lines=new ArrayList<LineNumberNode>();
            for(AbstractInsnNode node:redundantMethod.instructions) {
                if(node instanceof LineNumberNode line)lines.add(line);
            }
            assertFalse("Actual hooked donor has no source line map",lines.isEmpty());
            assertTrue("Positive fixture must have one actual source line",lines.stream().allMatch(line->line.line==lines.get(0).line));
            for(int at:new int[]{19,27}) {
                var label=new LabelNode();var duplicate=new InsnList();duplicate.add(label);duplicate.add(new LineNumberNode(lines.get(0).line,label));
                redundantMethod.instructions.insertBefore(redundantCode.get(at),duplicate);
            }
            var redundantWriter=new ClassWriter(0);redundant.accept(redundantWriter);
            PhpTraitPropertyHookOrigin.validateRuntimeTarget(source,redundantWriter.toByteArray(),owner,member,env.getScope().getClassLoader());
            assertNull(PhpRuntimeOwnership.current(env));
            for(String mutation:new String[]{"missing-origin","body","handler","trace-owner","trace-slot","strict","binder","receiver","numeric-profile","debug-line","debug-transition","debug-missing","debug-zero"}) {
                ClassNode forged=PhpTraitPropertyHookOrigin.readClass(actual);
                MethodNode method=forged.methods.stream().filter(value->value.name.equals(member)).findFirst().orElseThrow();
                var code=hookPrefix(method);
                if(mutation.equals("missing-origin"))method.attrs.removeIf(attribute->attribute.type.equals(PhpTraitPropertyHookOrigin.ATTRIBUTE));
                else if(mutation.equals("body"))method.instructions.insertBefore(code.get(code.size()-1),new InsnNode(Opcodes.NOP));
                else if(mutation.equals("handler")){assertFalse(method.tryCatchBlocks.isEmpty());method.tryCatchBlocks.get(0).type="java/lang/RuntimeException";}
                else if(mutation.equals("trace-owner"))((FieldInsnNode)code.get(1)).owner=forged.name;
                else if(mutation.equals("trace-slot"))((LdcInsnNode)code.get(2)).cst=((Integer)((LdcInsnNode)code.get(2)).cst)+1;
                else if(mutation.equals("strict"))method.instructions.set(code.get(4),new InsnNode(Opcodes.ICONST_0));
                else if(mutation.equals("binder"))((LdcInsnNode)code.get(23)).cst="unrelated-hook";
                else if(mutation.equals("receiver"))((VarInsnNode)code.get(19)).var=1;
                else if(mutation.equals("numeric-profile"))numericProfileLiteral(method).cst=99;
                else {
                    var first=Arrays.stream(method.instructions.toArray()).filter(node->node instanceof LineNumberNode)
                            .map(node->(LineNumberNode)node).findFirst().orElseThrow();
                    if(mutation.equals("debug-line"))first.line++;
                    else if(mutation.equals("debug-transition")) {
                        var label=new LabelNode();var transition=new InsnList();transition.add(label);transition.add(new LineNumberNode(first.line+1,label));
                        method.instructions.insertBefore(code.get(19),transition);
                    } else if(mutation.equals("debug-zero")) {
                        // JDK source-copy reader drops its synthetic 0 sentinel; an actual target cannot forge that debug interval.
                        var label=new LabelNode();var transition=new InsnList();transition.add(label);transition.add(new LineNumberNode(0,label));
                        method.instructions.insertBefore(code.get(19),transition);
                    } else for(AbstractInsnNode node:method.instructions.toArray())if(node instanceof LineNumberNode)method.instructions.remove(node);
                }
                var writer=new ClassWriter(0);forged.accept(writer);boolean rejected=false;
                try {PhpTraitPropertyHookOrigin.validateRuntimeTarget(source,writer.toByteArray(),owner,member,env.getScope().getClassLoader());}
                catch(Throwable failure){assertProtocolFailure(failure);rejected=true;}
                assertTrue(mutation+" accepted a modified actual runtime-copy target",rejected);
                assertNull(PhpRuntimeOwnership.current(env));
            }
        });
    }
    @Test public void runtimeHookSourceUsesOnlyExactWitnessedGraphAndActualLoader()throws Throwable {
        withRuntimeMiddle((env,leaf,middle) -> {
            var hook=middle.findProperty("p").getPhpHooks().get();boolean rejected=false;
            try {PhpTraitPropertyHookOrigin.runtimeSource(hook,env.getScope().getClassLoader(),List.of());}
            catch(Throwable failure){assertProtocolFailure(failure);rejected=true;}
            assertTrue("Unwitnessed donor was acquired through a generic lookup",rejected);
            rejected=false;var foreign=newScope().getClassLoader();
            try {PhpTraitPropertyHookOrigin.runtimeSource(hook,foreign,List.of(leaf,middle));}
            catch(Throwable failure){assertProtocolFailure(failure);rejected=true;}
            assertTrue("Foreign runtime loader accepted original/defined byte witness",rejected);
            assertNull(PhpRuntimeOwnership.current(env));
        });
    }
    private static void assertProtocolFailure(Throwable failure) {
        assertTrue("Unexpected implementation failure: "+failure,failure instanceof IOException || failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException || failure instanceof LinkageError);
    }
    @FunctionalInterface private interface RuntimeCheck {void run(Environment env,ClassEntity leaf,ClassEntity middle)throws Throwable;}
    private void withRuntimeMiddle(RuntimeCheck check)throws Throwable {
        String id="ordinary-host-leaf1-middle0-host0";
        try(var fixture=PhpNativeTestResources.fixture(KIT+"/"+id,"main.php",FILES)) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            try {
                for(String name:new String[]{"leaf.php","middle.php"}) {
                    var file=fixture.sibling(name).toFile();include(env,compile(env,file,source(id,name),false,false,PhpLanguageProfile.PHP_8_5),file);
                }
                ClassEntity leaf=env.fetchClass("Leaf",false,PhpLanguageProfile.PHP_8_5),middle=env.fetchClass("Middle",false,PhpLanguageProfile.PHP_8_5);
                assertNotNull(leaf);assertNotNull(middle);assertEquals(0,output.size());check.run(env,leaf,middle);
                assertEquals("Pair/graph verification executed PHP effects",0,output.size());assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
            }finally{env.doFinal();}
        }
    }
}
