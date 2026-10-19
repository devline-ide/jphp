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
public class PhpRawOffsetTest {
    private void group(String group) throws Throwable {
        var stream=getClass().getClassLoader().getResourceAsStream("resources/strings/php85_raw_offsets.tsv");
        assertNotNull(stream);int count=0;
        for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
            String[] pin=line.split("\t",-1);if(!pin[0].equals(group))continue;count++;
            String source=new String(Base64.getDecoder().decode(pin[2]),StandardCharsets.UTF_8);
            for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
                String label=pin[1]+",debug="+debug+",PHB="+dumped;
                var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
                scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
                scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.setErrorFlags(php.runtime.exceptions.support.ErrorType.E_ALL.value);var warnings=new ArrayList<String>();
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
    @Test public void nativeByteReads()throws Throwable{group("read");}
    @Test public void nativeByteWrites()throws Throwable{group("write");}
    @Test public void nativeOffsetRejections()throws Throwable{group("reject");}
    @Test public void nativeStringSteps()throws Throwable{group("step");}
    @Test public void legacyUnicodeOffsetRemainsJavaCharacter()throws Throwable {
        var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
        scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);var env=new Environment(scope,new ByteArrayOutputStream());
        try {var context=new Context("$s='Привет';return $s[0];");context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            assertEquals("П",module.include(env).toString());
        }finally{env.doFinal();}
    }
    @Test public void unmanagedOffsetCallCannotAdmitGeneratedFrame()throws Throwable {
        var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env=new Environment(scope,new ByteArrayOutputStream());var context=new Context("class ByteOffsetManaged{function read($s){return $s[0];}}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
        var clazz=env.fetchClass("ByteOffsetManaged",true,PhpLanguageProfile.PHP_8_5);clazz.initializeRuntimeState(env);var method=clazz.findMethod("read");var reflected=method.getNativeMethod();
        var plan=org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.read(clazz.getData(),reflected.getName(),php.runtime.PhpOwnershipExecution.descriptor(reflected));
        assertNotNull(plan);assertTrue(plan.unsupported().toString(),plan.unsupported().stream().anyMatch(x->x.contains("PhpStringOffsets")));
        var domain=new php.runtime.PhpOwnershipDomain();var participant=domain.participant(env);var session=new php.runtime.PhpOwnershipExecution.Session(participant,env);
        try(var execution=session.enter()) {
            try{session.registerGenerated(method,reflected,clazz.getData(),plan.runtimePlan(),(p,m,b)->{});fail("Unproved byte offset frame admitted");}
            catch(IllegalArgumentException expected){assertEquals("Incomplete or foreign generated frame plan",expected.getMessage());}
            assertNull(php.runtime.PhpOwnershipExecution.currentEntry(env));
        }finally{try{env.doFinal();}finally{participant.close();}}
    }
}
