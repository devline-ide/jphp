package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.env.handler.ErrorReportHandler;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual native pinned PHP source, compiled and independently transported through PHB. */
public class PhpRawBoundaryTest {
    private void group(String group) throws Throwable {
        var stream=getClass().getClassLoader().getResourceAsStream("resources/strings/php85_raw_boundaries.tsv");
        assertNotNull(stream);int count=0;
        for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
            String[] pin=line.split("\t",-1);if(!pin[0].equals(group))continue;count++;
            String source=new String(Base64.getDecoder().decode(pin[2]),StandardCharsets.UTF_8);
            for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
                String label=pin[1]+",debug="+debug+",PHB="+dumped;
                var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
                scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
                scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var warnings=new ArrayList<String>();
                env.setErrorReportHandler(new ErrorReportHandler(){
                    public boolean onError(SystemMessage message){warnings.add(message.getType().value+":"+message.getMessage());return true;}
                    public boolean onFatal(ErrorException failure){throw new AssertionError(failure);}
                });
                try {
                    var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module=new JvmCompiler(env,context).compile(false);
                    if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                    Memory result=module.include(env);env.flushAll();
                    assertEquals(label+",return",pin[3],HexFormat.of().formatHex(result.getBinaryBytes(StandardCharsets.UTF_8)));
                    assertEquals(label+",output",pin[4],HexFormat.of().formatHex(output.toByteArray()));
                    assertEquals(label+",warnings",new String(Base64.getDecoder().decode(pin[5]),StandardCharsets.UTF_8),String.join("\n",warnings));
                } finally { env.doFinal(); }
            }
        }
        assertTrue(group,count>0);
    }
    @Test public void nativeFoldedBinaryBytes() throws Throwable {group("fold");}
    @Test public void nativeStringableOutput() throws Throwable {group("echo");}
    @Test public void checkedStringableEnvelopeKeepsActualBytesAndReceiver() throws Throwable {
        var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
        var context=new Context("class ByteEchoManaged {function __toString(){return \"\\xff\";}}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
        var domain=new php.runtime.PhpOwnershipDomain();var participant=domain.participant(env);var session=new php.runtime.PhpOwnershipExecution.Session(participant,env);
        try(var execution=session.enter()) {
            var clazz=env.fetchClass("ByteEchoManaged",true,PhpLanguageProfile.PHP_8_5);clazz.initializeRuntimeState(env);
            var method=clazz.methodMagicToString;var reflected=method.getNativeMethod();
            var plan=org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.read(clazz.getData(),reflected.getName(),php.runtime.PhpOwnershipExecution.descriptor(reflected));
            assertNotNull(plan);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
            session.registerGenerated(method,reflected,clazz.getData(),plan.runtimePlan(),(expected,actualMethod,bytes)->{
                if(!actualMethod.equals(reflected))throw new IllegalArgumentException("Stringable reflected member changed");
                var decoded=org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.read(bytes,actualMethod.getName(),php.runtime.PhpOwnershipExecution.descriptor(actualMethod));
                if(!plan.equals(decoded))throw new IllegalArgumentException("Stringable callback plan changed");
            });
            var provider=session.objects().registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,php.runtime.reflection.ClassEntity.class),(c,b)->{});
            var allocation=session.objects().allocate(provider,env,Collections.emptyMap());var node=allocation.binding().node();
            int top=env.getCallStackTop();var trace=new TraceInfo(context);var actual=allocation.binding().rawValue();
            php.runtime.output.OutputBuffer.echoPhp85(env,actual,trace);
            assertEquals(top,env.getCallStackTop());assertNull(php.runtime.PhpOwnershipExecution.currentEntry(env));assertEquals(php.runtime.PhpOwnership.State.LIVE,node.state());
            env.flushAll();assertEquals("ff",HexFormat.of().formatHex(output.toByteArray()));allocation.close();assertEquals(php.runtime.PhpOwnership.State.DISPOSED,node.state());
        }finally{try{env.doFinal();}finally{participant.close();}}
    }
}
