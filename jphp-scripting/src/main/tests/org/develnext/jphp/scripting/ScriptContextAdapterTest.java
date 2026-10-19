package org.develnext.jphp.scripting;

import org.junit.Test;
import org.develnext.jphp.scripting.util.WriterOutputStream;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.output.OutputBuffer;
import php.runtime.reflection.ModuleEntity;

import javax.script.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.Assert.*;

/** Real document compilation and context transactions; execution belongs to the root runner. */
public class ScriptContextAdapterTest {
    @FunctionalInterface private interface Action {void run(JPHPScriptEngine engine,Environment env)throws Throwable;}
    private static void fixture(Action action)throws Throwable {fixture(false,action);}
    private static void fixture(boolean certifiedEnvironment,Action action)throws Throwable {
        String previous=System.getProperty("jphp.config");
        Path bootstrap=Files.createTempFile("jphp-context-bootstrap-",".php");
        Path config=Files.createTempFile("jphp-context-config-",".properties");
        JPHPScriptEngine engine=null;Throwable primary=null;
        try {
            Files.writeString(bootstrap,"<?php function context_bootstrap_read(){global $value;return $value;} return 0;",StandardCharsets.UTF_8);
            Properties properties=new Properties();properties.setProperty("bootstrap.file",bootstrap.toString());
            properties.setProperty("env.doFinal","0");properties.setProperty("env.extensions","spl");
            if(certifiedEnvironment)properties.setProperty("env.concurrent","0");
            try(OutputStream output=Files.newOutputStream(config)){properties.store(output,"isolated ScriptContext regression");}
            System.setProperty("jphp.config",config.toString());engine=new JPHPScriptEngine();
            var field=JPHPScriptEngine.class.getDeclaredField("environment");field.setAccessible(true);
            action.run(engine,(Environment)field.get(engine));
        }catch(Throwable failure){primary=failure;throw failure;}
        finally {
            try{if(engine!=null)engine.close();}catch(Throwable cleanup){if(primary==null)throw cleanup;if(primary!=cleanup)primary.addSuppressed(cleanup);}
            finally{if(previous==null)System.clearProperty("jphp.config");else System.setProperty("jphp.config",previous);Files.deleteIfExists(config);Files.deleteIfExists(bootstrap);}
        }
    }
    private static SimpleScriptContext context(Bindings engine,Bindings global,Writer writer) {
        SimpleScriptContext context=new SimpleScriptContext();context.setBindings(engine,ScriptContext.ENGINE_SCOPE);
        if(global!=null)context.setBindings(global,ScriptContext.GLOBAL_SCOPE);context.setWriter(writer);return context;
    }
    private static ModuleEntity module(CompiledScript script)throws Exception {
        var field=script.getClass().getDeclaredField("module");field.setAccessible(true);return (ModuleEntity)field.get(script);
    }
    private static final class TrackingWriter extends Writer {
        final StringBuilder text=new StringBuilder();int writes,flushes,closes;IOException writeFailure,flushFailure;
        @Override public void write(char[] value,int offset,int length)throws IOException {writes++;if(writeFailure!=null)throw writeFailure;text.append(value,offset,length);}
        @Override public void flush()throws IOException {flushes++;if(flushFailure!=null)throw flushFailure;}
        @Override public void close(){closes++;}
    }

    @Test public void bootstrapFunctionsGlobalStatementsAndGlobalsSeeSuppliedScope()throws Throwable {
        fixture((engine,env)->{
            engine.put("value","default");Bindings bindings=new SimpleBindings();bindings.put("value","supplied");
            StringWriter writer=new StringWriter();ScriptContext context=context(bindings,null,writer);
            assertEquals("supplied:changed",engine.eval("<?php echo context_bootstrap_read(); $GLOBALS['value']='changed'; return 'supplied:'.context_bootstrap_read();",context).toString());
            assertEquals("supplied",writer.toString());assertEquals("changed",bindings.get("value").toString());
            assertEquals("default",engine.eval("<?php return context_bootstrap_read();").toString());
        });
    }
    @Test public void nestedIncludesUseCanonicalSuppliedGlobalsAndPublishTheirWrites()throws Throwable {
        fixture((engine,env)->{
            Path inner=Files.createTempFile("jphp-context-inner-",".php"),outer=Files.createTempFile("jphp-context-outer-",".php");
            try {
                Files.writeString(inner,"<?php $GLOBALS['value']=$value.':inner'; echo $value;",StandardCharsets.UTF_8);
                Files.writeString(outer,"<?php include '"+inner.toString().replace('\\','/')+"'; $outerValue=$value.':outer';",StandardCharsets.UTF_8);
                Bindings bindings=new SimpleBindings();bindings.put("value","input");StringWriter writer=new StringWriter();
                assertEquals("input:inner:outer",engine.eval("<?php include '"+outer.toString().replace('\\','/')+"'; return $outerValue;",context(bindings,null,writer)).toString());
                assertEquals("input:inner",writer.toString());assertEquals("input:inner",bindings.get("value").toString());assertEquals("input:inner:outer",bindings.get("outerValue").toString());
                assertNull(engine.get("outerValue"));
            }finally{Files.deleteIfExists(inner);Files.deleteIfExists(outer);}
        });
    }
    @Test public void fallbackSameValueAssignmentPublishesOnlyToEngineScope()throws Throwable {
        fixture((engine,env)->{
            Bindings supplied=new SimpleBindings(),global=new SimpleBindings();global.put("fallback","same");global.put("readOnly","read");
            ScriptContext context=context(supplied,global,new StringWriter());
            assertEquals("read",engine.eval("<?php $fallback=$fallback; return $readOnly;",context).toString());
            assertEquals("same",supplied.get("fallback").toString());assertFalse(supplied.containsKey("readOnly"));assertEquals("same",global.get("fallback"));
            global.put("readOnly","new");assertEquals("new",engine.eval("<?php return $readOnly;",context).toString());
        });
    }
    @Test public void fallbackUnsetAndEngineUnsetKeepGlobalFallbackUnchanged()throws Throwable {
        fixture((engine,env)->{
            Bindings supplied=new SimpleBindings(),global=new SimpleBindings();supplied.put("same","engine");global.put("same","global");global.put("fallback","present");
            ScriptContext context=context(supplied,global,new StringWriter());
            assertEquals("engine",engine.eval("<?php $before=$same; unset($same,$fallback); return $before;",context).toString());
            assertFalse(supplied.containsKey("same"));assertFalse(supplied.containsKey("fallback"));assertEquals("global",global.get("same"));assertEquals("present",global.get("fallback"));
            assertEquals("global:present",engine.eval("<?php return $same.':'.$fallback;",context).toString());
        });
    }
    @Test public void defaultContextAndExplicitCanonicalBindingsPreserveGlobalsCarrier()throws Throwable {
        fixture((engine,env)->{
            ArrayMemory table=env.getRuntimeGlobalsTable();ReferenceMemory carrier=table.getByScalar("GLOBALS");
            engine.put("value","default");assertEquals("default",engine.compile("<?php return $GLOBALS['value'];").eval().toString());
            assertEquals("changed",engine.eval("<?php $value='changed';return context_bootstrap_read();",engine.getBindings(ScriptContext.ENGINE_SCOPE)).toString());
            assertSame(table,env.getRuntimeGlobalsTable());assertSame(carrier,table.getByScalar("GLOBALS"));assertSame(table,carrier.getRawValue());
            assertEquals("changed",engine.get("value").toString());
        });
    }
    @Test public void defaultCanonicalFallbackReadsDoNotPersistAndEqualWritesDo()throws Throwable {
        fixture((engine,env)->{
            Bindings global=new SimpleBindings();global.put("fallback","same");engine.setBindings(global,ScriptContext.GLOBAL_SCOPE);
            assertEquals("same",engine.eval("<?php return $fallback;").toString());assertNull(engine.get("fallback"));
            engine.eval("<?php $fallback=$fallback;");assertEquals("same",engine.get("fallback").toString());assertEquals("same",global.get("fallback"));
        });
    }
    @Test public void suppliedSuperglobalsUseSameTableAndDefaultValuesAreRestored()throws Throwable {
        fixture((engine,env)->{
            engine.put("_GET",Map.of("value","default"));
            Memory previous=env.getRuntimeGlobalsTable().getByScalar("_GET").toValue();
            Bindings supplied=new SimpleBindings();supplied.put("_GET",Map.of("value","supplied"));
            assertEquals("changed",engine.eval("<?php $_GET['value']='changed'; return $GLOBALS['_GET']['value'];",context(supplied,null,new StringWriter())).toString());
            assertEquals("changed",((Memory)supplied.get("_GET")).valueOfIndex(TraceInfo.UNKNOWN,StringMemory.valueOf("value")).toString());
            assertSame(previous,env.getRuntimeGlobalsTable().getByScalar("_GET").toValue());
        });
    }
    @Test public void globalsReplacementRejectsBeforeConversionAndWriterEffects()throws Throwable {
        fixture((engine,env)->{
            engine.put("value","default");ArrayMemory table=env.getRuntimeGlobalsTable();ReferenceMemory self=table.getByScalar("GLOBALS");
            Bindings bindings=new SimpleBindings();bindings.put("GLOBALS",Map.of());bindings.put("value","supplied");TrackingWriter writer=new TrackingWriter();
            ScriptException failure=assertThrows(ScriptException.class,()->engine.eval("<?php echo 'entered';$value='changed';",context(bindings,null,writer)));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);assertEquals("default",engine.get("value").toString());assertSame(self,table.getByScalar("GLOBALS"));
            assertEquals(0,writer.writes);assertEquals(0,writer.flushes);assertEquals(0,writer.closes);
        });
    }
    @Test public void phpFailurePublishesWritesAndRestoresDefaultAndCallerThread()throws Throwable {
        fixture((engine,env)->{
            engine.put("value","default");Bindings supplied=new SimpleBindings();supplied.put("value","supplied");
            Environment caller=new Environment(new CompileScope(),new ByteArrayOutputStream());
            try(Environment.ThreadBinding binding=caller.bindCurrentThread()) {
                ScriptException failure=assertThrows(ScriptException.class,()->engine.eval("<?php $value='failed-write'; throw new \\RuntimeException('context failure');",context(supplied,null,new StringWriter())));
                assertEquals("context failure",failure.getMessage().split(" in ")[0]);assertSame(caller,Environment.current());
                assertEquals("failed-write",supplied.get("value").toString());assertEquals("default",engine.get("value").toString());
                assertEquals("default",engine.eval("<?php return $value;").toString());assertSame(caller,Environment.current());
            }finally{caller.doFinal();caller.releaseThreadSupport();}
        });
    }
    @Test public void originalPendingOutputAndBufferStackAreRestored()throws Throwable {
        fixture((engine,env)->{
            OutputBuffer root=env.getDefaultBuffer();root.setImplicitFlush(false);root.write("caller-pending");byte[] pending=root.getContents().getBinaryBytes(StandardCharsets.UTF_8);OutputStream output=root.getOutput();
            OutputBuffer nested=env.pushOutputBuffer(null,0,true);nested.write("nested-pending");byte[] nestedPending=nested.getContents().getBinaryBytes(StandardCharsets.UTF_8);
            List<OutputBuffer> buffers=new ArrayList<>(env.getOutputBuffers());TrackingWriter writer=new TrackingWriter();
            engine.eval("<?php echo 'context'; ob_start(); echo ':inner';",context(new SimpleBindings(),null,writer));
            assertEquals("context:inner",writer.text.toString());assertEquals(buffers,env.getOutputBuffers());assertSame(root,env.getDefaultBuffer());assertSame(output,root.getOutput());
            assertArrayEquals(pending,root.getContents().getBinaryBytes(StandardCharsets.UTF_8));assertArrayEquals(nestedPending,nested.getContents().getBinaryBytes(StandardCharsets.UTF_8));assertEquals(0,writer.closes);
        });
    }
    @Test public void writerFailureIsExactCauseAndCallerOutputRestores()throws Throwable {
        fixture((engine,env)->{
            OutputBuffer root=env.getDefaultBuffer();OutputStream output=root.getOutput();Bindings supplied=new SimpleBindings();supplied.put("value","input");
            TrackingWriter writer=new TrackingWriter();IOException exact=new IOException("host write failed");writer.writeFailure=exact;
            ScriptException failure=assertThrows(ScriptException.class,()->engine.eval("<?php echo 'fail';",context(supplied,null,writer)));
            assertSame(exact,failure.getCause());assertSame(output,root.getOutput());assertEquals(0,writer.closes);
            assertEquals("7",engine.eval("<?php return 7;").toString());
        });
    }
    @Test public void cleanupWriterFailureIsSuppressedOnExactPhpPrimary()throws Throwable {
        fixture((engine,env)->{
            env.getDefaultBuffer().setImplicitFlush(false);TrackingWriter writer=new TrackingWriter();IOException exact=new IOException("host cleanup write failed");writer.writeFailure=exact;
            ScriptException failure=assertThrows(ScriptException.class,()->engine.eval("<?php echo 'pending';throw new \\RuntimeException('PHP primary');",context(new SimpleBindings(),null,writer)));
            assertTrue(failure.getCause() instanceof php.runtime.lang.exception.BaseBaseException);assertArrayEquals(new Throwable[]{exact},failure.getCause().getSuppressed());assertEquals(0,writer.closes);
        });
    }
    @Test public void writerIncrementallyDecodesSplitUtf8AndNeverClosesHost()throws Throwable {
        TrackingWriter writer=new TrackingWriter();WriterOutputStream stream=new WriterOutputStream(writer,StandardCharsets.UTF_8);
        byte[] data="Я🙂尾".getBytes(StandardCharsets.UTF_8);for(byte value:data){stream.write(value);stream.flush();}
        stream.finish();stream.close();assertEquals("Я🙂尾",writer.text.toString());assertEquals(0,writer.closes);
    }
    @Test public void writerPreservesFirstFailureWithoutRetryOrReplacement()throws Throwable {
        TrackingWriter writer=new TrackingWriter();IOException exact=new IOException("first");writer.writeFailure=exact;
        WriterOutputStream stream=new WriterOutputStream(writer,StandardCharsets.UTF_8);
        assertSame(exact,assertThrows(IOException.class,()->stream.write('x')));assertSame(exact,assertThrows(IOException.class,stream::flush));stream.finish();stream.close();
        assertSame(exact,stream.failure());assertEquals(1,writer.writes);assertEquals(0,writer.flushes);assertEquals(0,writer.closes);
    }
    @Test public void writerFlushFailureIsExactAndIncompleteTailGetsReplacement()throws Throwable {
        TrackingWriter writer=new TrackingWriter();WriterOutputStream stream=new WriterOutputStream(writer,StandardCharsets.UTF_8);
        stream.write(new byte[]{(byte)0xE2,(byte)0x82});stream.finish();assertEquals("\uFFFD",writer.text.toString());assertEquals(0,writer.closes);
        TrackingWriter broken=new TrackingWriter();IOException exact=new IOException("flush");broken.flushFailure=exact;
        WriterOutputStream other=new WriterOutputStream(broken,StandardCharsets.UTF_8);assertSame(exact,assertThrows(IOException.class,other::flush));other.finish();assertSame(exact,other.failure());
    }
    @Test public void sameValueWritesThroughCanonicalReferenceAreObservedAndObserverRestores()throws Throwable {
        fixture((engine,env)->{
            ArrayMemory globals=env.getRuntimeGlobalsTable();globals.put("observed",LongMemory.valueOf(5));ReferenceMemory carrier=globals.getByScalar("observed");
            try(var outer=carrier.observeGlobalsWrites(env,"observed",carrier)) {
                try(var inner=carrier.observeGlobalsWrites(env,"observed",carrier)){carrier.assign(LongMemory.valueOf(5));assertTrue(inner.wasWritten());assertTrue(outer.wasWritten());}
                carrier.assign(LongMemory.valueOf(6));assertTrue(outer.wasWritten());
            }
            assertNull(carrier.getRuntimeHolder());assertEquals(6,carrier.toLong());
        });
    }
    @Test public void certifiedSourceAndPhbExportUseActualPublishersAndRestoreOriginalRoot()throws Throwable {
        for(boolean dump:new boolean[]{false,true})fixture(true,(engine,env)->{
            assertSame(Environment.class,env.getClass());
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);env.getDefaultBuffer().setImplicitFlush(false);
            ArrayMemory globals=env.getRuntimeGlobalsTable();for(Object key:new ArrayList<>(globals.keySet()))if(!key.equals("GLOBALS"))globals.removeByScalar(key);
            globals.put("source",LongMemory.valueOf(41));ModuleEntity compiled=module(engine.compile("<?php $export=[]; return $source;"));
            if(dump){ModuleDumper dumper=new ModuleDumper(compiled.getContext(),env,true);ByteArrayOutputStream bytes=new ByteArrayOutputStream();dumper.save(compiled,bytes);compiled=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));env.getScope().loadModule(compiled);env.getScope().addUserModule(compiled);env.registerModule(compiled);}
            PhpOwnershipDomain domain=new PhpOwnershipDomain();PhpRuntimeOwnership.Authority authority=new PhpRuntimeOwnership.Authority(env,domain);
            ScriptContextScope.HostRoots roots=new ScriptContextScope.HostRoots();
            boolean[] authorityClosed={false};
            try {
                ReferenceMemory cell;
                try(var execution=authority.enter()) {
                    authority.registerModule(compiled,compiled.getData(),(p,m,bytes)->{});cell=authority.globalSlot("source").reference();
                    authority.globalSlot("alias").carrier().assignRef(cell);
                }
                ReferenceMemory original=globals.getByScalar("source");Bindings supplied=new SimpleBindings();supplied.put("source",7);TrackingWriter writer=new TrackingWriter();
                try(var thread=env.bindCurrentThread();var scope=ScriptContextScope.open(env,context(supplied,null,writer),false,roots,compiled)){assertEquals(7,scope.include(compiled).toLong());}
                assertSame(original,globals.getByScalar("source"));assertEquals(41,original.toLong());assertSame(cell,original.getRawValue());assertSame(cell,globals.getByScalar("alias").getRawValue());assertFalse(globals.containsKey("export"));
                ArrayMemory exported=((Memory)supplied.get("export")).toValue(ArrayMemory.class);assertNotNull(exported.getRuntimeOrigin());assertTrue(domain.graph().liveNodes()>0);
                roots.close(env);try(var execution=authority.enter()){authority.close();authorityClosed[0]=true;}assertEquals(0,domain.graph().liveNodes());assertEquals(0,writer.closes);
            }finally{if(!authorityClosed[0])try(var execution=authority.enter()){roots.close(env);authority.close();}}
        });
    }
    @Test public void inadmittedOwnedModuleRejectsBeforeBindingConversionOrWriterEffects()throws Throwable {
        fixture((engine,env)->{
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);ModuleEntity compiled=module(engine.compile("<?php echo 'entered';return 1;"));
            PhpOwnershipDomain domain=new PhpOwnershipDomain();PhpRuntimeOwnership.Authority authority=new PhpRuntimeOwnership.Authority(env,domain);
            Bindings supplied=new SimpleBindings();supplied.put("opaque",new Object());TrackingWriter writer=new TrackingWriter();List<OutputBuffer> previous=new ArrayList<>(env.getOutputBuffers());
            try {
                try{ScriptContextScope.open(env,context(supplied,null,writer),false,new ScriptContextScope.HostRoots(),compiled);fail("Inadmitted module ran");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Module has no complete ordinary producer protocol"));}
                assertEquals(0,writer.writes);assertEquals(0,writer.flushes);assertEquals(0,writer.closes);assertEquals(previous,env.getOutputBuffers());assertEquals(0,domain.graph().liveNodes());
            }finally{try(var execution=authority.enter()){authority.close();}}
        });
    }
    @Test public void explicitSessionRejectsBeforeBindingsAndWriterAreRead()throws Throwable {
        fixture((engine,env)->{
            ModuleEntity compiled=module(engine.compile("<?php echo 'entered';return 1;"));TrackingWriter writer=new TrackingWriter();
            PhpOwnershipDomain domain=new PhpOwnershipDomain();var participant=domain.participant(env);var session=new PhpOwnershipExecution.Session(participant,env);
            int[] reads={0};SimpleScriptContext supplied=new SimpleScriptContext() {
                @Override public Bindings getBindings(int scope){reads[0]++;return super.getBindings(scope);}
                @Override public Writer getWriter(){reads[0]++;return super.getWriter();}
            };supplied.setWriter(writer);
            try(var execution=session.enter()) {
                try{ScriptContextScope.open(env,supplied,false,new ScriptContextScope.HostRoots(),compiled);fail("Unsupported explicit-session adapter admitted");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("explicit-session native-globals host protocol"));}
            }
            participant.close();assertEquals(0,reads[0]);assertEquals(0,writer.writes);assertEquals(0,writer.flushes);assertEquals(0,writer.closes);assertEquals(0,domain.graph().liveNodes());
        });
    }
    @Test public void uncertifiedArrayInputRejectsBeforeGlobalsRetirementAndWriterEffects()throws Throwable {
        fixture(true,(engine,env)->{
            assertSame(Environment.class,env.getClass());
            env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);ModuleEntity compiled=module(engine.compile("<?php return 1;"));
            ArrayMemory table=env.getRuntimeGlobalsTable();for(Object key:new ArrayList<>(table.keySet()))if(!key.equals("GLOBALS"))table.removeByScalar(key);
            table.put("saved",LongMemory.valueOf(41));ReferenceMemory self=table.getByScalar("GLOBALS"),saved=table.getByScalar("saved");
            PhpOwnershipDomain domain=new PhpOwnershipDomain();PhpRuntimeOwnership.Authority authority=new PhpRuntimeOwnership.Authority(env,domain);
            try {
                try(var execution=authority.enter()){authority.registerModule(compiled,compiled.getData(),(p,m,bytes)->{});}
                Bindings bindings=new SimpleBindings();bindings.put("opaque",new ArrayMemory());TrackingWriter writer=new TrackingWriter();
                try{ScriptContextScope.open(env,context(bindings,null,writer),false,new ScriptContextScope.HostRoots(),compiled);fail("Uncertified input array admitted");}
                catch(IllegalArgumentException expected){}
                assertSame(self,table.getByScalar("GLOBALS"));assertSame(saved,table.getByScalar("saved"));assertEquals(41,saved.toLong());assertFalse(env.getSyntheticGlobalsOrigin().isRetired());
                assertEquals(0,writer.writes);assertEquals(0,writer.flushes);assertEquals(0,writer.closes);assertEquals(0,domain.graph().liveNodes());
            }finally{try(var execution=authority.enter()){authority.close();}}
        });
    }
}
