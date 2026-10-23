package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Modifier;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.exceptions.CustomErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.core.classes.stream.MiscStream;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ModuleEntity;
import static org.junit.Assert.*;

/** Real WrapModule constructor/call and PHP catch boundaries, with independently compiled inner PHB. */
public class PhpDeprecatedStaticBridgeTest extends JvmCompilerCase {
    private Environment environment(boolean debug,ByteArrayOutputStream output) {
        var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope,output);
    }
    private ModuleEntity compile(Environment env,String code,boolean phb,boolean debug) throws Throwable {
        var context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(phb) {
            var dumper=new ModuleDumper(context,env,debug);var bytes=new ByteArrayOutputStream();
            dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        return module;
    }
    private byte[] inner(String code,boolean phb,boolean debug) throws Throwable {
        if(!phb)return ("<?php "+code).getBytes(StandardCharsets.UTF_8);
        var env=environment(debug,new ByteArrayOutputStream());
        try {
            var module=compile(env,code,false,debug);var bytes=new ByteArrayOutputStream();
            new ModuleDumper(module.getContext(),env,debug).save(module,bytes);
            return bytes.toByteArray(); // No inner include, registration or PHP effects during preparation.
        }finally{env.doFinal();}
    }
    private static String module(boolean phb) {
        return "new \\php\\lang\\Module($GLOBALS['bridge_stream'],"+(phb?"true":"false")+",true,'bridge-inner.php')";
    }
    private static boolean has(Throwable failure,Class<? extends Throwable> type) {
        for(Throwable current=failure;current!=null;current=current.getCause())if(type.isInstance(current))return true;
        return false;
    }
    @Test public void normalWrapConstructPublishesAndCallKeepsOriginalSemantics() throws Throwable {
        for(boolean phb:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=environment(debug,output);
            try {
                assertTrue(Modifier.isFinal(Environment.class.getMethod("__checkClassDeclaration",TraceInfo.class,String.class,String.class).getModifiers()));
                var input=inner("class BridgeNormalStatic{} echo 'inner;';return 7;",phb,debug);
                env.getGlobals().refOfIndex("bridge_stream").assign(new ObjectMemory(new MiscStream(env,new ByteArrayInputStream(input))));
                var outer=compile(env,"$m="+module(phb)+";echo 'before=',class_exists('BridgeNormalStatic',false)?1:0,';';$value=$m->call();echo 'value=',$value,';';",phb,debug);
                env.getScope().loadModule(outer);env.getScope().addUserModule(outer);env.registerModule(outer);outer.include(env);env.flushAll();
                assertEquals("SOURCE/PHB="+phb+" debug="+debug,"before=1;inner;value=7;",output.toString(StandardCharsets.UTF_8));
            }finally{env.doFinal();}
        }
    }
    @Test public void reservedCollisionIsFatalAndPhpThrowableCannotCatchIt() throws Throwable {
        for(boolean phb:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=environment(debug,output);
            try {
                var input=inner("class BridgeFatalConsumer{} echo 'inner-after;';",phb,debug);
                env.getGlobals().refOfIndex("bridge_stream").assign(new ObjectMemory(new MiscStream(env,new ByteArrayInputStream(input))));
                String code="#[Deprecated('bridge')] trait BridgeFatalTrait{}set_error_handler(function($n,$m){echo 'handler;';$inner="+module(phb)+";try{echo 'inner-enter;';$inner->call();echo 'returned;';}catch(Throwable $e){echo 'caught;';}return true;});class BridgeFatalConsumer{use BridgeFatalTrait;}echo 'outer-after;';";
                var outer=compile(env,code,phb,debug);env.getScope().loadModule(outer);env.getScope().addUserModule(outer);env.registerModule(outer);
                Throwable failure=null;try{outer.include(env);}catch(Throwable actual){failure=actual;}env.flushAll();
                assertNotNull(failure);assertTrue(String.valueOf(failure),has(failure,CustomErrorException.class));
                for(Throwable current=failure;current!=null;current=current.getCause())if(current instanceof CustomErrorException fatal)assertEquals(ErrorType.E_ERROR,fatal.getType());
                assertEquals("handler;inner-enter;",output.toString(StandardCharsets.UTF_8));
                assertNull(env.fetchClass("BridgeFatalConsumer",false,PhpLanguageProfile.PHP_8_5));
            }finally{env.doFinal();}
        }
    }
    @Test public void moduleSkippedDuringReservationCannotPublishOnLateCallAfterRollback() throws Throwable {
        for(boolean phb:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=environment(debug,output);
            try {
                var input=inner("class BridgeLateConsumer{} echo 'inner-after;';",phb,debug);
                env.getGlobals().refOfIndex("bridge_stream").assign(new ObjectMemory(new MiscStream(env,new ByteArrayInputStream(input))));
                String code="#[Deprecated('bridge')] trait BridgeLateTrait{}set_error_handler(function($n,$m){echo 'handler;';$GLOBALS['saved']="+module(phb)+";throw new Exception('stop');});function bridgeLateDeclare(){class BridgeLateConsumer{use BridgeLateTrait;}}try{bridgeLateDeclare();}catch(Exception $e){echo 'rollback;';}restore_error_handler();echo 'visible=',class_exists('BridgeLateConsumer',false)?1:0,';';try{$GLOBALS['saved']->call();echo 'returned;';}catch(Throwable $e){echo 'caught;';}echo 'late-after;';";
                var outer=compile(env,code,phb,debug);env.getScope().loadModule(outer);env.getScope().addUserModule(outer);env.registerModule(outer);
                Throwable failure=null;try{outer.include(env);}catch(Throwable actual){failure=actual;}env.flushAll();
                assertNotNull(failure);assertTrue(String.valueOf(failure),has(failure,CriticalException.class));
                assertEquals("handler;rollback;visible=0;",output.toString(StandardCharsets.UTF_8));
                assertNull(env.fetchClass("BridgeLateConsumer",false,PhpLanguageProfile.PHP_8_5));
            }finally{env.doFinal();}
        }
    }
}
