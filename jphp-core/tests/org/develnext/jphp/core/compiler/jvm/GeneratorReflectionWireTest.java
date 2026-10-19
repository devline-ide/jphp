package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.lang.Generator;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.PhpGeneratorLinkageDumper;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.helper.GeneratorEntity;
import php.runtime.reflection.support.AbstractFunctionEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual shared provisional ABI17 transport; historical ABI16 fixtures were captured by ABI16 JARs. */
public class GeneratorReflectionWireTest extends JvmCompilerCase {
    @Override protected php.runtime.env.CompileScope newScope() {
        var scope = super.newScope(); scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension()); return scope;
    }
    private record Artifact(Context context, ModuleEntity source, byte[] phb, byte[] linkage) { }
    private String resource(String suffix) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("resources/generators/php_generator_reflection" + suffix)) {
            assertNotNull(in); return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private PhpGeneratorLinkageDumper.ModuleResolver resolver(Environment env) {
        return name -> env.getScope().moduleIndexMap.get(name);
    }
    private Artifact artifact(Environment env, String source, PhpLanguageProfile profile) throws Throwable {
        Context context = new Context(source.startsWith("<?php") ? source.substring(5) : source);
        context.setPhpLanguageProfile(profile);
        ModuleEntity module = new JvmCompiler(env,context).compile(false);
        byte[] linkage = PhpGeneratorLinkageDumper.save(module,resolver(env));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); new ModuleDumper(context,env,true).save(module,bytes);
        return new Artifact(context,module,bytes.toByteArray(),linkage);
    }
    private static int generatorFrameOffset(byte[] dump) {
        int result=-1;
        for(int at=4;at+12<=dump.length;at++) {
            var frame=java.nio.ByteBuffer.wrap(dump,at,12);
            if(frame.getInt()!=0x474c4e4b || frame.getInt()!=1)continue;
            int length=frame.getInt();if(length<0 || length>16*1024*1024 || length>dump.length-at-12)continue;
            int end=at+12+length;
            boolean suffix=end==dump.length;
            if(!suffix && end+12<=dump.length) {
                int attributes=java.nio.ByteBuffer.wrap(dump,end,4).getInt();
                suffix=attributes>=8 && attributes==dump.length-end-4
                        && java.nio.ByteBuffer.wrap(dump,end+4,4).getInt()==0x41544d44;
            }
            if(suffix){assertEquals("unique actual GLNK frame",-1,result);result=at;}
        }
        assertTrue("actual bounded GLNK frame",result>=4);return result;
    }
    private byte[] framed(Artifact artifact, byte[] linkage) throws IOException {
        int at=generatorFrameOffset(artifact.phb);
        assertTrue(at>0);DataInputStream in=new DataInputStream(new ByteArrayInputStream(artifact.phb,at,12));
        assertEquals(0x474c4e4b,in.readInt());assertEquals(1,in.readInt());assertEquals(artifact.linkage.length,in.readInt());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(artifact.phb,0,at);DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x474c4e4b);out.writeInt(1);out.writeInt(linkage.length);out.write(linkage);
        int end=at+12+artifact.linkage.length;
        bytes.write(artifact.phb,end,artifact.phb.length-end);return bytes.toByteArray();
    }
    private ModuleEntity load(Environment env, Artifact artifact, boolean linked) throws IOException {
        for(int at=0;at<50;at++)env.getScope().nextClassIndex();
        byte[] bytes=linked?artifact.phb:framed(artifact,new byte[0]);
        assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(bytes)).getCompilerAbiVersion());
        ModuleEntity module=new ModuleDumper(artifact.context,env,true).load(new ByteArrayInputStream(bytes));
        assertEquals("real PHB reader restores linkage before publication",linked,module.hasValidatedGeneratorLinkage());
        assertFalse(module.isLoaded());return module;
    }
    private ModuleEntity historical(Environment env,PhpLanguageProfile profile,boolean donor)throws IOException {
        String path="resources/generators/historical16/"+profile.name()+(donor?"_donor":"")+".phb";
        byte[] bytes;try(InputStream in=getClass().getClassLoader().getResourceAsStream(path)){assertNotNull(in);bytes=in.readAllBytes();}
        assertEquals(16,ModuleDumper.readHeader(new ByteArrayInputStream(bytes)).getCompilerAbiVersion());
        ModuleEntity module=new ModuleDumper(new Context("return 0;"),env,true).load(new ByteArrayInputStream(bytes));
        assertFalse(module.hasValidatedGeneratorLinkage());assertEquals(16,module.getCompilerProvenance().getCompilerAbiVersion());
        return module;
    }
    private void publish(Environment env, ModuleEntity module) {
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
    }
    private ModuleEntity executable(Environment env, String source, PhpLanguageProfile profile, boolean dumped) throws Throwable {
        Artifact artifact = artifact(env,source,profile);
        ModuleEntity module = dumped ? load(env,artifact,true) : artifact.source;
        publish(env,module); return module;
    }
    private void reflection(boolean dumped) throws Throwable {
        List<String> failures = new ArrayList<>();
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false,true}) {
            var scope = newScope(); scope.setDebugMode(debug);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); Environment env = new Environment(scope,output);
            try {
                String suffix = profile == PhpLanguageProfile.JPHP_LEGACY ? "_legacy" : "";
                ModuleEntity module = executable(env,resource(suffix + ".php"),profile,dumped);
                module.include(env); env.getDefaultBuffer().flush();
                assertEquals("native Reflection and generator execution bytes",resource(suffix + ".expected"),output.toString(StandardCharsets.UTF_8));
                assertTrue(module.hasValidatedGeneratorLinkage());
                for (ClosureEntity closure : module.getClosures())
                    assertSame("both closure metadata carriers agree",closure.getGeneratorEntity(),closure.methodMagicInvoke.getGeneratorEntity());
            } catch (Throwable failure) { failures.add(profile + " debug=" + debug + " dumped=" + dumped + " " + failure); }
            finally { env.doFinal(); }
        }
        assertTrue(String.join("\n",failures),failures.isEmpty());
    }
    @Test public void lexicalClassIdCollisionNeverSerializesInheritedGeneratorMethodsAsOwned() throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);Environment env=new Environment(scope,new ByteArrayOutputStream());
            try {
                ClassEntity builtin=env.fetchClass("Generator",false);int collision=Math.toIntExact(builtin.getId());assertTrue(collision>=0 && collision<100);
                StringBuilder source=new StringBuilder();for(int at=0;at<=collision+1;at++)source.append("function collision_").append(at).append("(){yield ").append(at).append(";return ").append(1000+at).append(";}");
                source.append("return [");for(int at=0;at<=collision+1;at++)source.append(at==0?"":",").append("collision_").append(at).append("()");source.append("];");
                Artifact artifact=artifact(env,source.toString(),profile);GeneratorEntity colliding=artifact.source.findGenerator(collision);
                assertEquals(builtin.getId(),colliding.getId());assertSame(builtin,colliding.findMethod("valid").getClazz());
                for(MethodEntity method:colliding.getOwnedMethods())assertNotSame("inherited native method must never be serialized as owned",builtin,method.getClazz());
                ModuleEntity loaded=load(env,artifact,true);publish(env,loaded);ArrayMemory objects=loaded.include(env).toValue(ArrayMemory.class);
                for(int at=0;at<objects.size();at++){
                    Generator generator=objects.valueOfIndex(at).toObject(Generator.class);assertEquals(at,generator.current(env).toLong());generator.next(env);
                    assertFalse(generator.valid(env).toBoolean());assertEquals(1000+at,generator.getReturn(env).toLong());
                    assertSame(builtin,generator.getReflection().findMethod("valid").getClazz());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void sourceReflectionReportsRealDeclarationsAndRunsNestedGenerators() throws Throwable { reflection(false); }
    @Test public void realPhbReaderRestoresReflectionAndExecution() throws Throwable { reflection(true); }

    private GeneratorEntity exact(Memory value, ModuleEntity owner) {
        Generator object = value.toObject(Generator.class); GeneratorEntity metadata = (GeneratorEntity)object.getReflection();
        assertSame(owner,metadata.getModule()); assertSame(object.getClass(),metadata.getNativeClass());
        int matches = 0; for (GeneratorEntity generator : owner.getGenerators()) if (generator == metadata) matches++;
        assertEquals("exact donor pointer occurs once",1,matches);
        assertEquals(object.getClass().getName().replace('.','/'),metadata.getCompiledInternalName().replace('.','/'));
        return metadata;
    }
    private void drive(Environment env, ArrayMemory objects, String prefix, ModuleEntity donor, ModuleEntity local, StringBuilder bytes) throws Throwable {
        for (int at = 0; at < objects.size(); at++) {
            Memory value = objects.valueOfIndex(at); exact(value,prefix.equals("consumer-") && at >= 2 ? local : donor);
            Generator generator = value.toObject(Generator.class);
            bytes.append(prefix).append(at).append(':').append(generator.current(env)).append(':').append(generator.send(env,LongMemory.valueOf(7))).append(':');
            generator.next(env); bytes.append(generator.valid(env).toBoolean() ? "valid" : "done").append(':').append(generator.getReturn(env)).append('\n');
        }
    }
    private void crossModule(boolean donorDumped) throws Throwable {
        List<String> failures = new ArrayList<>();
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean debug : new boolean[]{false,true})
                for (boolean consumerDumped : new boolean[]{false,true}) {
            var scope = newScope(); scope.setDebugMode(debug);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); Environment env = new Environment(scope,output);
            try {
                ModuleEntity donor = executable(env,resource("_donor.php"),profile,donorDumped);
                ArrayMemory donorObjects = donor.include(env).toValue(ArrayMemory.class);
                GeneratorEntity generator = exact(donorObjects.valueOfIndex(0),donor); Class<?> donorClass = generator.getNativeClass();
                assertSame(generator,env.fetchClass("ExternalOrderingTrait",false).findMethod("externalgenerator").getGeneratorEntity());
                ModuleEntity consumer = executable(env,resource("_consumer.php"),profile,consumerDumped);
                ArrayMemory consumerObjects = consumer.include(env).toValue(ArrayMemory.class);
                assertSame(generator,exact(consumerObjects.valueOfIndex(0),donor)); assertSame(generator,exact(consumerObjects.valueOfIndex(1),donor));
                assertSame(donor,generator.getModule()); assertSame(donorClass,generator.getNativeClass());
                assertEquals(2,donor.getGenerators().size()); assertEquals(3,consumer.getGenerators().size());
                var consumerClass = env.fetchClass("ExternalOrderingConsumer",false);
                assertSame(generator,consumerClass.findMethod("externalgenerator").getGeneratorEntity());
                assertSame(generator,consumerClass.findMethod("externalalias").getGeneratorEntity());
                executable(env,resource("_cross_probe.php"),profile,false).include(env); env.getDefaultBuffer().flush();
                StringBuilder actual = new StringBuilder(output.toString(StandardCharsets.UTF_8));
                drive(env,donorObjects,"donor-",donor,consumer,actual); drive(env,consumerObjects,"consumer-",donor,consumer,actual);
                assertEquals("native cross-module Reflection and bound-this/send/return bytes",resource("_cross.expected"),actual.toString());
            } catch (Throwable failure) { failures.add(profile + " debug=" + debug + " donorDumped=" + donorDumped + " consumerDumped=" + consumerDumped + " " + failure); }
            finally { env.doFinal(); }
        }
        assertTrue(String.join("\n",failures),failures.isEmpty());
    }
    @Test public void sourceTraitDonorLinksSurviveAllConsumerRoutes() throws Throwable { crossModule(false); }
    @Test public void phbTraitDonorLinksSurviveAllConsumerRoutes() throws Throwable { crossModule(true); }

    private static final String OLD = "function old_link_g(){yield 7;} function old_link_plain(){return old_link_g();} "
            + "class OldLinkClass {function generate(){yield 8;}function ordinary(){return old_link_g();}} "
            + "$old_link_closure=function(){yield 9;};return 0;";
    @Test public void oldAbi16NeverFabricatesFalseGeneratorMetadata() throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); Environment env = new Environment(newScope(),output);
            try {
                ModuleEntity old = historical(env,profile,false); publish(env,old); old.include(env);
                try { PhpGeneratorLinkageDumper.save(old,resolver(env)); fail("Old metadata was silently certified"); }
                catch (DumpException expected) { assertTrue(expected.getMessage().contains("recompilation")); }
                String query = "$rows=[new ReflectionFunction('old_link_g'),new ReflectionFunction('old_link_plain'),"
                        + "new ReflectionMethod('OldLinkClass','generate'),new ReflectionMethod('OldLinkClass','ordinary'),"
                        + "new ReflectionFunction($old_link_closure),new ReflectionFunction((new ReflectionFunction('old_link_g'))->getClosure())];"
                        + "foreach($rows as $row){try{$row->isGenerator();echo 'fabricated';}catch(ReflectionException $e){echo 'unavailable', \"\\n\";}}"
                        + "echo (new ReflectionFunction('strlen'))->isGenerator()?'true':'false';";
                executable(env,query,profile,false).include(env); env.getDefaultBuffer().flush();
                assertEquals("unavailable\nunavailable\nunavailable\nunavailable\nunavailable\nunavailable\nfalse",output.toString(StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        }
    }
    @Test public void oldForeignTraitMetadataCannotCertifyConsumerFactories() throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); Environment env = new Environment(newScope(),output);
            try {
                ModuleEntity donor = historical(env,profile,true); publish(env,donor); donor.include(env);
                Context context = new Context(resource("_consumer.php").substring(5)); context.setPhpLanguageProfile(profile);
                ModuleEntity consumer = new JvmCompiler(env,context).compile(false);
                if (profile == PhpLanguageProfile.JPHP_LEGACY) {
                    try { PhpGeneratorLinkageDumper.save(consumer,resolver(env)); fail("Unvalidated foreign factory was certified"); }
                    catch (DumpException expected) { assertTrue(expected.getMessage().contains("donor linkage unavailable")); }
                }
                publish(env,consumer); consumer.include(env);
                String query="foreach(['externalGenerator','externalAlias','externalOrdinary'] as $name){"
                        + "try{(new ReflectionMethod('ExternalOrderingConsumer',$name))->isGenerator();echo 'fabricated';}"
                        + "catch(ReflectionException $e){echo 'unavailable',\"\\n\";}}";
                executable(env,query,profile,false).include(env); env.getDefaultBuffer().flush();
                assertEquals("unavailable\nunavailable\nunavailable\n",output.toString(StandardCharsets.UTF_8));
                assertFalse(donor.hasValidatedGeneratorLinkage());
            } finally { env.doFinal(); }
        }
    }

    private record Declaration(int kind, String physical, String phpName, String body, String module, int ordinal, String generator) {
        Declaration factory(String module, int ordinal, String generator) { return new Declaration(kind,physical,phpName,body,module,ordinal,generator); }
    }
    private static final class Wire {
        int stamp,version,abi,profile; String module; final List<String> generators = new ArrayList<>(); final List<Declaration> declarations = new ArrayList<>();
        Wire(byte[] bytes) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); stamp=in.readInt();version=in.readInt();module=in.readUTF();abi=in.readInt();profile=in.readInt();
            int count=in.readInt();for(int i=0;i<count;i++)generators.add(in.readUTF());
            count=in.readInt();for(int i=0;i<count;i++)declarations.add(new Declaration(in.readUnsignedByte(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readInt(),in.readUTF()));
            assertEquals(-1,in.read());
        }
        byte[] bytes() throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(stamp);out.writeInt(version);out.writeUTF(module);out.writeInt(abi);out.writeInt(profile);out.writeInt(generators.size());for(String name:generators)out.writeUTF(name);
            out.writeInt(declarations.size());for(Declaration d:declarations){out.writeByte(d.kind);out.writeUTF(d.physical);out.writeUTF(d.phpName);out.writeUTF(d.body);out.writeUTF(d.module);out.writeInt(d.ordinal);out.writeUTF(d.generator);}out.flush();return bytes.toByteArray();
        }
    }
    private List<AbstractFunctionEntity> declarations(ModuleEntity module) {
        List<AbstractFunctionEntity> result = new ArrayList<>(module.getFunctions());
        for (ClosureEntity closure : module.getClosures()) result.add(closure.methodMagicInvoke);
        for (ClassEntity clazz : module.getClasses()) result.addAll(clazz.getOwnedMethods());
        for (ClassEntity clazz : module.getDeferredClasses()) result.addAll(clazz.getOwnedMethods()); return result;
    }
    private void unlinked(ModuleEntity module) {
        assertFalse(module.hasValidatedGeneratorLinkage());
        for (AbstractFunctionEntity declaration : declarations(module)) assertNull("no partial declaration mutation",declaration.getGeneratorEntity());
        for (ClosureEntity closure : module.getClosures()) assertNull("no partial closure mutation",closure.getGeneratorEntity());
    }
    private void reject(Environment env, Artifact artifact, byte[] bytes) throws IOException {
        ModuleEntity loaded=load(env,artifact,false);
        try { PhpGeneratorLinkageDumper.restore(loaded,bytes,resolver(env)); fail("Malformed linkage accepted"); }
        catch (IOException expected) { unlinked(loaded); }
    }
    @Test public void realPhbFrameAndMalformedLinkageRejectBeforePublication() throws Throwable {
        for(PhpLanguageProfile profile:PhpLanguageProfile.values()){
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());try{
                Artifact artifact=artifact(env,GUARD,profile);
                for(int mode=0;mode<7;mode++){
                    byte[] phb;
                    if(mode<3){phb=artifact.phb.clone();int at=generatorFrameOffset(phb);
                        if(mode==0)phb[at+3]++;if(mode==1)phb[at+7]++;if(mode==2)java.nio.ByteBuffer.wrap(phb,at+8,4).putInt(16*1024*1024+1);
                    }else if(mode==3)phb=Arrays.copyOf(artifact.phb,artifact.phb.length-1);
                    else{Wire wire=new Wire(artifact.linkage);
                        if(mode==4){Declaration first=wire.declarations.getFirst();wire.declarations.set(0,first.factory(wire.module,1,wire.generators.get(1)));}
                        if(mode==5)wire.abi++;if(mode==6)wire.declarations.removeLast();phb=framed(artifact,wire.bytes());}
                    int modules=env.getScope().moduleIndexMap.size();
                    try{new ModuleDumper(artifact.context,env,true).load(new ByteArrayInputStream(phb));fail("Malformed actual PHB linkage frame accepted");}
                    catch(IOException expected){assertEquals("failed reader never publishes module",modules,env.getScope().moduleIndexMap.size());}
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void historical16RedumpTo17RetainsUnavailableMetadataAndOriginalCompilerAbi()throws Throwable {
        for(PhpLanguageProfile profile:PhpLanguageProfile.values()){
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());try{
                ModuleEntity old=historical(env,profile,false);ByteArrayOutputStream bytes=new ByteArrayOutputStream();new ModuleDumper(old.getContext(),env,true).save(old,bytes);
                assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());
                ModuleEntity redump=new ModuleDumper(old.getContext(),env,true).load(new ByteArrayInputStream(bytes.toByteArray()));
                assertEquals(16,redump.getCompilerProvenance().getCompilerAbiVersion());assertFalse(redump.hasValidatedGeneratorLinkage());
                for(AbstractFunctionEntity declaration:declarations(redump))assertNull(declaration.getGeneratorEntity());
            }finally{env.doFinal();}
        }
    }
    private static final String GUARD = "function guard_a($x=1){yield $x;return 10;}function guard_b($x=2){yield $x;return 20;}"
            +"function guard_plain(){return guard_a();}class GuardClass{function method(){yield 30;}function plain(){return guard_a();}}"
            +"$guard_closure=function(){yield 40;};return 0;";
    @Test public void malformedCompleteCatalogsFailAtomicallyIncludingValidWrongOrdinals() throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            Environment env = new Environment(newScope(),new ByteArrayOutputStream());
            try {
                Artifact artifact=artifact(env,GUARD,profile);
                for(int mode=0;mode<14;mode++){
                    Wire wire=new Wire(artifact.linkage); Declaration first=wire.declarations.getFirst();
                    switch(mode){
                        case 0 -> wire.stamp++;
                        case 1 -> wire.version++;
                        case 2 -> wire.module+="_foreign";
                        case 3 -> wire.abi++;
                        case 4 -> wire.profile++;
                        case 5 -> Collections.swap(wire.generators,0,1);
                        case 6 -> wire.declarations.removeLast();
                        case 7 -> wire.declarations.set(1,first);
                        case 8 -> wire.declarations.set(0,new Declaration(first.kind,first.physical,first.phpName,first.body+"_foreign",first.module,first.ordinal,first.generator));
                        case 9 -> wire.declarations.set(0,first.factory(first.module,1,wire.generators.get(1)));
                        case 10 -> {int at=0;while(wire.declarations.get(at).ordinal!=-1)at++;var ordinary=wire.declarations.get(at);wire.declarations.set(at,ordinary.factory(wire.module,0,wire.generators.getFirst()));}
                        case 11 -> wire.declarations.set(0,first.factory("",-1,""));
                        case 12 -> wire.declarations.set(0,first.factory(first.module,Integer.MAX_VALUE,first.generator));
                        case 13 -> wire.declarations.set(0,first.factory(first.module,first.ordinal,first.generator+"_foreign"));
                    }
                    reject(env,artifact,wire.bytes());
                }
                reject(env,artifact,Arrays.copyOf(artifact.linkage,artifact.linkage.length+1));
                for(int cut:new int[]{0,4,12,artifact.linkage.length-1})reject(env,artifact,Arrays.copyOf(artifact.linkage,cut));
                reject(env,artifact,new byte[16*1024*1024+1]);
            } finally {env.doFinal();}
        }
    }
    @Test public void metadataOnlyArtifactCannotBypassPhysicalBytecodeChecks() throws Throwable {
        Environment env=new Environment(newScope(),new ByteArrayOutputStream());
        try{
            Artifact artifact=artifact(env,GUARD,PhpLanguageProfile.PHP_8_5);
            ModuleDumper dumper=new ModuleDumper(artifact.context,env,true);dumper.setIncludeData(false);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();dumper.save(artifact.source,bytes);
            ModuleEntity loaded=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            try{PhpGeneratorLinkageDumper.restore(loaded,artifact.linkage,resolver(env));fail("Metadata-only artifact certified without bytecode");}
            catch(IOException expected){unlinked(loaded);}
        }finally{env.doFinal();}
    }
    private void changeLookup(FunctionEntity function, String module, int ordinal) {
        ClassNode node=new ClassNode();new ClassReader(function.getData()).accept(node,0);int changed=0;
        for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions.toArray())
            if(instruction instanceof MethodInsnNode invoke && invoke.owner.equals("php/runtime/env/Environment") && invoke.name.equals("__getGenerator")){
                AbstractInsnNode oldOrdinal=instruction.getPrevious(),oldModule=oldOrdinal.getPrevious();
                method.instructions.set(oldModule,new LdcInsnNode(module));method.instructions.set(oldOrdinal,new LdcInsnNode(ordinal));changed++;
            }
        assertEquals(1,changed);ClassWriter out=new ClassWriter(0);node.accept(out);function.setData(out.toByteArray());
    }
    @Test public void actualFactoryWrongOrdinalAndForeignDonorCannotPassPhysicalAttestation() throws Throwable {
        Environment env=new Environment(newScope(),new ByteArrayOutputStream());
        try{
            Artifact artifact=artifact(env,GUARD,PhpLanguageProfile.PHP_8_5);Wire wire=new Wire(artifact.linkage);
            ModuleEntity loaded=load(env,artifact,false);changeLookup(loaded.findFunction("guard_a"),loaded.getInternalName(),1);
            Declaration first=wire.declarations.getFirst();wire.declarations.set(0,first.factory(wire.module,1,wire.generators.get(1)));
            try{PhpGeneratorLinkageDumper.restore(loaded,wire.bytes(),resolver(env));fail("Valid wrong index plus matching forged metadata accepted");}
            catch(DumpException expected){assertTrue(expected.getMessage().contains("constructor mismatch"));unlinked(loaded);}
            Artifact donorArtifact=artifact(env,"function donor_physical(){yield 91;}return 0;",PhpLanguageProfile.PHP_8_5);
            ModuleEntity donor=load(env,donorArtifact,true);publish(env,donor);
            loaded=load(env,artifact,false);changeLookup(loaded.findFunction("guard_a"),donor.getInternalName(),0);
            wire=new Wire(artifact.linkage);first=wire.declarations.getFirst();wire.declarations.set(0,first.factory(donor.getInternalName(),0,donor.findGenerator(0).getCompiledInternalName()));
            try{PhpGeneratorLinkageDumper.restore(loaded,wire.bytes(),resolver(env));fail("Valid foreign donor index plus metadata accepted despite local NEW");}
            catch(DumpException expected){assertTrue(expected.getMessage().contains("constructor mismatch"));unlinked(loaded);}
            // Classfile data cannot be substituted even if all PHP names, signatures and ordinals exist.
            loaded=load(env,artifact,false);loaded.findFunction("guard_a").setData(loaded.findFunction("guard_b").getData());
            try{PhpGeneratorLinkageDumper.restore(loaded,artifact.linkage,resolver(env));fail("Wrong declaration physical class accepted");}
            catch(DumpException expected){assertTrue(expected.getMessage().contains("classfile identity"));unlinked(loaded);}
            loaded=load(env,artifact,false);loaded.findGenerator(0).setData(loaded.findGenerator(1).getData());
            try{PhpGeneratorLinkageDumper.restore(loaded,artifact.linkage,resolver(env));fail("Wrong generator physical class accepted");}
            catch(DumpException expected){assertTrue(expected.getMessage().contains("classfile identity"));unlinked(loaded);}
        }finally{env.doFinal();}
    }
    @Test public void pointerDisagreementPartialPopulationAndReplayAreRejected() throws Throwable {
        Environment env=new Environment(newScope(),new ByteArrayOutputStream());
        try{
            ModuleEntity unknown=new ModuleEntity(new Context("return 0;"));
            assertFalse("unknown provenance does not fabricate source completeness",unknown.hasValidatedGeneratorLinkage());
            try{PhpGeneratorLinkageDumper.save(unknown);fail("Unknown metadata certified");}catch(DumpException expected){assertTrue(expected.getMessage().contains("recompilation"));}
            Artifact artifact=artifact(env,GUARD,PhpLanguageProfile.PHP_8_5);FunctionEntity original=artifact.source.findFunction("guard_a");GeneratorEntity pointer=original.getGeneratorEntity();
            original.setGeneratorEntity(artifact.source.findGenerator(1));
            try{PhpGeneratorLinkageDumper.save(artifact.source,resolver(env));fail("Source pointer mismatch accepted");}
            catch(DumpException expected){assertTrue(expected.getMessage().contains("source generator pointer"));}finally{original.setGeneratorEntity(pointer);}
            ModuleEntity loaded=load(env,artifact,false);loaded.findFunction("guard_b").setGeneratorEntity(loaded.findGenerator(1));
            try{PhpGeneratorLinkageDumper.restore(loaded,artifact.linkage,resolver(env));fail("Partial metadata accepted");}
            catch(DumpException expected){assertFalse(loaded.hasValidatedGeneratorLinkage());assertNull(loaded.findFunction("guard_a").getGeneratorEntity());}
            loaded=load(env,artifact,true);GeneratorEntity restored=loaded.findFunction("guard_a").getGeneratorEntity();
            assertSame(loaded.findGenerator(0),restored);
            try{PhpGeneratorLinkageDumper.restore(loaded,artifact.linkage,resolver(env));fail("Component replay accepted");}
            catch(DumpException expected){assertSame(restored,loaded.findFunction("guard_a").getGeneratorEntity());assertTrue(loaded.hasValidatedGeneratorLinkage());}
        }finally{env.doFinal();}
    }
}