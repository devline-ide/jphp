package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.ext.core.classes.stream.MiscStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** Actual PHP8.5.11 oracles, genuine parameter dispatch and consumed nested arguments. */
public class PhpNoDiscardDynamicNestedTest extends JvmCompilerCase {
    private static final String KIT="nodiscard-dynamic";
    private static final Map<String,String> SOURCE_PINS=Map.of(
            "unknown-dispatcher","d4a1e914481d04f331c7a09609055ae8635fe6be08d3021c43129192f6d052e5",
            "nested-consumed-arguments","18decada0d134df8bd78116381909d570d9d8d982f233c3c1772f7796cab0766",
            "nullsafe-method-result-use","13ec7f9aadefbf769de0b2098789587cc05113ab872abfe73b9721db20450de0");
    private static final Map<String,String> STDOUT_PINS=Map.of(
            "unknown-dispatcher","269349308f883c985033b9b05bd5a66c4d8051bd9fe30f137f21e82be199136e",
            "nested-consumed-arguments","2f8300d96f836117cd71fa0c762c41cec11714d2ce720a28b9a9100c9e70e7b3",
            "nullsafe-method-result-use","4ae8aa68448a662bbcb3ed625116da4eee0e481e67e15ac2dc0ee5f55d48cd87");
    private static String hash(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void requireUnknownDispatcherBytecode(Environment env,String name) {
        var function=env.fetchFunction(name);assertNotNull(function);
        var type=new ClassNode();new ClassReader(function.getData()).accept(type,0);
        int generic=0;
        for(var method:type.methods)for(var operation:method.instructions)if(operation instanceof MethodInsnNode call) {
            if(call.owner.equals("php/runtime/invoke/PhpCallFrame")&&call.name.equals("callable"))generic++;
            assertFalse("Parameter dispatcher must not become a literal native bridge",
                    call.owner.equals("php/runtime/ext/core/LangFunctions")
                            &&(call.name.equals("call_user_funcDiscarded")||call.name.equals("call_user_func_arrayDiscarded")));
        }
        assertTrue("Three parameter calls retain generic callable acquisition",generic>=3);
    }
    private void exact(String id,long nativeWarnings)throws Throwable {
        byte[] sourceBytes=PhpNativeTestResources.read(KIT+"/native/"+id+".php");
        byte[] expected=PhpNativeTestResources.read(KIT+"/native-run/"+id+".stdout");
        byte[] expectedStderr=PhpNativeTestResources.read(KIT+"/native-run/"+id+".stderr");
        assertEquals(SOURCE_PINS.get(id),hash(sourceBytes));
        assertEquals(STDOUT_PINS.get(id),hash(expected));
        assertEquals("Native oracle has no unhandled diagnostics",0,expectedStderr.length);
        assertEquals("Actual native warning count",nativeWarnings,
                new String(expected,StandardCharsets.UTF_8).lines().filter(line->line.startsWith("W|")).count());
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var fixture=PhpNativeTestResources.fixture(KIT+"/native",id+".php",id+".php")) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var stdout=new ByteArrayOutputStream();var stderr=new ByteArrayOutputStream();
            var env=new Environment(scope,stdout);
            env.getDefaultBuffer().setChunkSize(0);
            env.defineConstant("STDERR",ObjectMemory.valueOf(new MiscStream(env,stderr)),true);
            try {
                var context=new Context(new String(sourceBytes,StandardCharsets.UTF_8),fixture.file());
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                module.include(env);env.flushAll();
                String label=id+" debug="+debug+" PHB="+phb+" actual="+stdout.toString(StandardCharsets.UTF_8);
                assertArrayEquals(label,expected,stdout.toByteArray());
                assertArrayEquals(label+" genuine stderr",expectedStderr,stderr.toByteArray());
                assertNull(env.getErrorHandler());
                if(id.equals("unknown-dispatcher")){
                    requireUnknownDispatcherBytecode(env,"relay");
                    requireUnknownDispatcherBytecode(env,"relay_array");
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void unknownParameterAndFirstClassNativeDispatchMatchNativeUsedCallbackResult()throws Throwable {
        // Actual native PHP emits zero warnings for these unknown internal dispatcher routes.
        exact("unknown-dispatcher",0);
    }
    @Test public void nestedFunctionAndMethodArgumentsConsumeTheirInnerResultsBeforeOuterWarning()throws Throwable {
        // Native order: V|discarded-outer, outer warning at line29, then M|discarded-outer.
        exact("nested-consumed-arguments",1);
    }
    @Test public void nullsafeMethodResultUseAndSkippedArgumentsMatchNativeExactTrace()throws Throwable {
        // Only non-null discarded call warns at line19. Null receiver never evaluates any argument.
        exact("nullsafe-method-result-use",1);
    }
}
