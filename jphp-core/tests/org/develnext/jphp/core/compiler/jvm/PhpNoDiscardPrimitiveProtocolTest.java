package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.core.classes.stream.MiscStream;
import php.runtime.loader.RuntimeClassLoader;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** Real CELL callers, exact definition witnesses and captured PHP8.5 warning effects. */
public class PhpNoDiscardPrimitiveProtocolTest extends JvmCompilerCase {
    private static final String KIT="nodiscard-primitive";
    private static final String SOURCE="$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;"
            +"function leaf(&$w){$w=6;}function borrow(&$v){leaf($v);}borrow($r);unset($r);"
            +"$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;";
    private static final Map<String,String> PINS=Map.of(
            "positive.php","e9ce2193512a8a702f2524204bc15724018c7f2318865924914e9a25eb0210aa",
            "positive.stdout","90ca36701badb49bff8167ad13a465a5ee215227de8ab99a132bc5862606789d",
            "handler-bootstrap.php","70f2059cfe32f0776610095262087920f15d8875f29bad0cdac40bf61ffb4064",
            "handler-main.php","ef299a05750c38417f83d9f1fe5d808a2f5d4780d26953ed6df484faa261de64",
            "handler.stdout","5be49373f7b92e9bfa1672a0d4a1aa528d8b5b2233e7c4f5e5dd224add6a37ec",
            "message-bootstrap.php","d88d70f8851636780ba54dffb879ad8e15d6d210a0d81fefbfd102859204e8b6",
            "message-main.php","2e6e3ce7359cee76259217f38fa607320681644620dbf8058ff3bf0ad9c2c620",
            "message.stdout","5744d52ed493e4ebbcf06a53c21d1cc697d9d54ed1553ec11928fae08ef68eaa");
    private record Body(Class<?> owner,String name,String descriptor,byte[] bytes) {}
    private record Call(String owner,String name,String descriptor) {}

    private static byte[] resource(String name)throws Exception {
        byte[] bytes=PhpNativeTestResources.read(KIT+"/"+name);
        String pin=PINS.get(name);
        if(pin!=null)assertEquals(name,pin,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return bytes;
    }
    private static void register(Environment env,ModuleEntity module) {
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
    }
    private static ModuleEntity compile(Environment env,Context context,boolean phb,boolean debug)throws Throwable {
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);
            codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
        register(env,module);return module;
    }
    private static Body body(java.lang.reflect.Method method) {
        Class<?> owner=method.getDeclaringClass();var loader=(RuntimeClassLoader)owner.getClassLoader();
        byte[] bytes=loader.primitiveDefinedBytes(owner);assertNotNull("Actual source/PHB defined-input witness",bytes);
        assertTrue(loader.matchesDefinedBytecode(owner,bytes));
        return new Body(owner,method.getName(),PhpOwnershipExecution.descriptor(method),bytes);
    }
    private static PhpPrimitiveCellPlan.Member closed(Body body)throws IOException {
        var member=PhpPrimitiveCellPlan.read(body.owner(),body.bytes()).get(body.name()+body.descriptor());
        assertNotNull("Actual CELL member exists",member);assertTrue("Actual CELL member is closed",member.closed());
        assertTrue(member.actualMember(body.owner(),body.name(),body.descriptor()));return member;
    }
    private static MethodNode method(Body body,ClassNode node) {
        return node.methods.stream().filter(value->value.name.equals(body.name())&&value.desc.equals(body.descriptor())).findFirst().orElseThrow();
    }
    private static ClassNode tree(byte[] bytes) {var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static boolean discarded(MethodInsnNode call) {
        return call.name.equals("invokeDiscarded")&&call.owner.equals("php/runtime/invoke/PhpCallFrame")
                ||call.name.equals("invokeToEvalDiscarded")&&(call.owner.equals("php/runtime/invoke/PhpCallFrame")
                ||call.owner.equals("php/runtime/PhpRuntimeOwnership$FunctionPacket")
                ||call.owner.equals("php/runtime/PhpRuntimeOwnership$CallPacket"));
    }
    private static Set<Call> calls(Body body) {
        Set<Call> calls=new LinkedHashSet<>();
        for(var op:method(body,tree(body.bytes())).instructions)if(op instanceof MethodInsnNode call&&discarded(call)) {
            assertEquals("Actual emitted invocation opcode",Opcodes.INVOKEVIRTUAL,call.getOpcode());
            calls.add(new Call(call.owner,call.name,call.desc));
        }
        return calls;
    }
    @Test public void sourceAndPhbClosedNestedCallsRetireBothActualPackets()throws Throwable {
        assertEquals("<?php\n"+SOURCE,new String(resource("positive.php"),StandardCharsets.UTF_8));
        assertEquals(0,resource("positive.stderr").length);
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var module=compile(env,new Context(SOURCE),phb,debug);
                Body caller=body(module.getNativeMethod());closed(caller);assertFalse("Actual discarded call exists",calls(caller).isEmpty());
                for(var function:module.getFunctions())closed(body(function.getNativeMethod()));
                module.include(env);env.flushAll();
                assertArrayEquals("debug="+debug+" PHB="+phb,resource("positive.stdout"),output.toByteArray());
                assertNull("Passive execution never enables whole graph ownership",PhpRuntimeOwnership.current(env));
                assertFalse("Outer return retires sparse custody",PhpPrimitiveCellRuntime.active(env));
            }finally{env.doFinal();}
        }
    }
    private static byte[] changed(Body body,Call target,int mutation) {
        var node=tree(body.bytes());MethodInsnNode selected=null;
        for(var op:method(body,node).instructions)if(op instanceof MethodInsnNode call
                &&call.owner.equals(target.owner())&&call.name.equals(target.name())&&call.desc.equals(target.descriptor())) {selected=call;break;}
        assertNotNull(selected);
        switch(mutation) {
            case 0 -> selected.setOpcode(Opcodes.INVOKESTATIC);
            case 1 -> {selected.setOpcode(Opcodes.INVOKEINTERFACE);selected.itf=true;}
            case 2 -> selected.setOpcode(Opcodes.INVOKESPECIAL);
            case 3 -> selected.desc=selected.desc.substring(0,selected.desc.indexOf(')')+1)+"Ljava/lang/Object;";
            case 4 -> selected.owner="java/lang/String";
            case 5 -> selected.name="invokeDiscardedForeignSibling";
            default -> throw new AssertionError("Unknown physical mutation");
        }
        var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
    }
    /** Uses the real guarded definition path; never manufactures or changes its provenance hashes. */
    private static boolean accepted(Body original,byte[] bytes,byte[] archive,Context context,Environment env,boolean debug)throws Throwable {
        try(var loader=new RuntimeClassLoader(original.owner().getClassLoader())) {
            // The reader also proves real closure constructors in the same defining loader.
            // Fresh PHB metadata prevents changing the original fixture's ClassEntity identities.
            var dependencies=new ModuleDumper(context,env,debug).load(new ByteArrayInputStream(archive));
            for(var closure:dependencies.getClosures())loader.loadClass(closure,true);
            String internal=new ClassReader(bytes).getClassName();
            var define=RuntimeClassLoader.class.getDeclaredMethod("defineOrReuse",String.class,byte[].class);define.setAccessible(true);
            Class<?> owner;
            try {owner=(Class<?>)define.invoke(loader,internal,bytes);}
            catch(InvocationTargetException failure) {
                if(failure.getCause() instanceof LinkageError)return false;
                throw failure.getCause();
            }
            assertSame("Actual negative definition belongs to its own RuntimeClassLoader",loader,owner.getClassLoader());
            assertTrue("Changed bytes themselves have exact defined provenance",loader.matchesDefinedBytecode(owner,bytes));
            try {
                var member=PhpPrimitiveCellPlan.read(owner,bytes).get(original.name()+original.descriptor());
                return member!=null&&member.closed()&&member.actualMember(owner,original.name(),original.descriptor());
            }catch(IOException|LinkageError rejected){return false;}
        }
    }
    @Test public void physicallyChangedDiscardedCallsCannotMintClosedCellCustody()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context(SOURCE);var module=compile(env,context,phb,debug);
                var archive=new ByteArrayOutputStream();new ModuleDumper(context,env,debug).save(module,archive);
                List<Body> bodies=new ArrayList<>();bodies.add(body(module.getNativeMethod()));
                for(var function:module.getFunctions())bodies.add(body(function.getNativeMethod()));
                int witnessed=0;
                for(Body actual:bodies) {
                    closed(actual);assertTrue("Cold exact-definition control",accepted(actual,actual.bytes(),archive.toByteArray(),context,env,debug));
                    for(Call call:calls(actual)) {
                        witnessed++;
                        for(int mutation=0;mutation<6;mutation++)
                            assertFalse(call+" mutation="+mutation+" debug="+debug+" PHB="+phb,accepted(actual,changed(actual,call,mutation),archive.toByteArray(),context,env,debug));
                    }
                }
                assertTrue("Real caller and nested callee emit discarded calls",witnessed>=2);
            }finally{env.doFinal();}
        }
    }
    @Test public void warningHandlersAndAttributeMessagesRetainCellsLikeNativePhp()throws Throwable {
        for(String route:new String[]{"handler","message"})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})
                try(var fixture=PhpNativeTestResources.fixture(KIT,route+"-main.php",route+"-bootstrap.php",route+"-main.php")) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var output=new ByteArrayOutputStream();var stderr=new ByteArrayOutputStream();var env=new Environment(scope,output);
            env.getDefaultBuffer().setChunkSize(0);
            env.defineConstant("STDERR",ObjectMemory.valueOf(new MiscStream(env,stderr)),true);
            try {
                // Handler registration finishes before the certified main caller starts sparse custody.
                var bootstrap=compile(env,new Context(new String(resource(route+"-bootstrap.php"),StandardCharsets.UTF_8),
                        fixture.sibling(route+"-bootstrap.php").toFile()),phb,debug);
                bootstrap.include(env);
                var main=compile(env,new Context(new String(resource(route+"-main.php"),StandardCharsets.UTF_8),fixture.file()),phb,debug);
                closed(body(main.getNativeMethod()));
                main.include(env);env.flushAll();
                assertArrayEquals(route+" debug="+debug+" PHB="+phb,resource(route+".stdout"),output.toByteArray());
                assertArrayEquals(route+" genuine stderr",resource(route+".stderr"),stderr.toByteArray());
                assertNull(PhpRuntimeOwnership.current(env));assertFalse(PhpPrimitiveCellRuntime.active(env));
            }finally{env.doFinal();}
        }
    }
}
