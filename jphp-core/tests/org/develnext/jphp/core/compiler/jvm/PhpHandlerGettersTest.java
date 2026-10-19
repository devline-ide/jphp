package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
public final class PhpHandlerGettersTest extends JvmCompilerCase {
    @Test public void exactNativeHandlerValuesMasksNullAndLifoSourcePhbDebug()throws Throwable {
        int rows=0;
        for(String mode:List.of("weak","strict"))for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            String path="handler-getters/"+mode;
            try {
                String source=PhpNativeTestResources.text(path+".php");
                var context=new Context(source.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.getDefaultBuffer().flush();
                assertArrayEquals(mode+" debug="+debug+" PHB="+phb,PhpNativeTestResources.read(path+".stdout"),output.toByteArray());
                assertEquals(0,PhpNativeTestResources.read(path+".stderr").length);rows+=31;
            }finally{env.doFinal();}
        }
        assertEquals(248,rows);
    }
    @Test public void gettersStayModernOnlyAndCannotClaimAnUnadmittedOwnedResult()throws Throwable {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);var env=new Environment(scope,new ByteArrayOutputStream());
        try {
            assertNull(env.fetchFunction("get_error_handler"));
            assertNull(env.fetchFunction("get_exception_handler"));
            assertNotNull(scope.fetchPhp85Function("get_error_handler"));
            assertNotNull(scope.fetchPhp85Function("get_exception_handler"));
            for(String name:List.of("get_error_handler","get_exception_handler")) {
                var member=php.runtime.ext.core.Php85LangFunctions.class.getMethod(name,Environment.class);
                try {php.runtime.PhpOwnershipNativeCalls.requireCapability(member);fail("Getter acquired unsupported explicit native custody");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("Native member has no complete ownership call contract"));}
            }
            var domain=new php.runtime.PhpOwnershipDomain();
            var session=new php.runtime.PhpOwnershipExecution.Session(domain.participant(env),env);
            var beforeError=env.getErrorHandler();var beforeException=env.getExceptionHandler();
            try(var execution=session.enter()) {
                for(String name:List.of("get_error_handler","get_exception_handler"))try(var roots=session.newCallRoots()) {
                    try {roots.invokeFunction(scope.fetchPhp85Function(name),TraceInfo.UNKNOWN);fail("Unadmitted actual native getter invoked");}
                    catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("Native member has no complete ownership call contract"));}
                }
                try {php.runtime.ext.core.Php85LangFunctions.get_error_handler(env);fail("Direct Session getter bypassed native admission");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("explicit native read/result contract"));}
                try {php.runtime.ext.core.Php85LangFunctions.get_exception_handler(env);fail("Direct Session exception getter bypassed native admission");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("owned FIELD read/result contract"));}
                assertSame(beforeError,env.getErrorHandler());assertSame(beforeException,env.getExceptionHandler());
                assertEquals(0,domain.graph().liveNodes());
            }
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var legacyRepeat=env.fetchFunction("str_repeat");
            assertNotNull(legacyRepeat);
            assertTrue(((php.runtime.reflection.CompileFunctionEntity)legacyRepeat).hasCompletePhpSignature());
            assertSame(php.runtime.Memory.NULL,legacyRepeat.invoke(env,TraceInfo.UNKNOWN,new php.runtime.Memory[0]));
            assertEquals("str_repeat() expects at least 2 parameter(s), 0 given",env.getLastMessage().getMessage());
            assertSame(php.runtime.Memory.NULL,legacyRepeat.invoke(env,TraceInfo.UNKNOWN,new php.runtime.Memory[]{
                    php.runtime.memory.StringMemory.valueOf("x"),php.runtime.memory.LongMemory.valueOf(2),php.runtime.memory.LongMemory.valueOf(3)}));
            assertEquals("str_repeat() expects exactly 2 parameter(s), 3 given",env.getLastMessage().getMessage());
            var actual=env.getErrorHandler();
            try {php.runtime.PhpOwnershipNativeCalls.currentErrorHandlerResult(env,new php.runtime.env.handler.ErrorHandler(null,null,0));fail("Foreign current error FIELD accepted");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("actual Environment FIELD"));}
            assertSame(actual,env.getErrorHandler());
            try {php.runtime.PhpOwnershipNativeCalls.currentExceptionHandlerResult(env,new php.runtime.env.handler.ExceptionHandler(null,null));fail("Foreign exception FIELD accepted");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("actual Environment FIELD"));}
        }finally{env.doFinal();}
    }
    @Test public void directDynamicOptionalVariadicArityKeepsAllArgumentEffectsSourcePhbDebug()throws Throwable {
        int rows=0;
        for(String mode:List.of("weak","strict"))for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            String path="handler-getters-arity/"+mode;
            try {
                String source=PhpNativeTestResources.text(path+".php");
                var context=new Context(source.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.getDefaultBuffer().flush();
                assertArrayEquals(mode+" debug="+debug+" PHB="+phb,PhpNativeTestResources.read(path+".stdout"),output.toByteArray());
                assertEquals(0,PhpNativeTestResources.read(path+".stderr").length);rows+=18;
            }finally{env.doFinal();}
        }
        assertEquals(144,rows);
    }

}
