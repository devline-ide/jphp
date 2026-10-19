package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.java.JavaException;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import java.io.*;
import java.util.List;
import static org.junit.Assert.*;

/** Real ordinary gateways preserve PHP exception translation; the outer catch caller is passive. */
public class PhpRuntimeExceptionTransportTest extends JvmCompilerCase {
    private enum Route { FUNCTION, METHOD, MODULE, CLOSURE }
    private static final ThreadLocal<Fixture> CURRENT=new ThreadLocal<>();
    public static final class TransportExtension extends Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new TransportFunctions());}
    }
    public static final class TransportFunctions extends FunctionsContainer {
        public static Memory transport_fail(Environment env)throws Throwable {
            Fixture fixture=CURRENT.get();assertSame(fixture.env,env);fixture.calls++;throw fixture.primary;
        }
        public static Memory transport_bridge(Environment env)throws Throwable {
            assertNull("Outer PHP catch caller is explicitly passive",PhpRuntimeOwnership.current(env));
            return CURRENT.get().invoke();
        }
    }
    private final class Fixture implements AutoCloseable {
        final Environment env;final PhpOwnershipDomain domain=new PhpOwnershipDomain();final ModuleEntity module;
        final Route route;final Throwable primary;final PhpRuntimeOwnership.Authority authority;
        final PhpOwnershipDomain.Execution execution;PhpRuntimeOwnership.Returned closureResult;ObjectMemory closure;
        int calls;
        Fixture(boolean debug,boolean dump,Route route,boolean managed,Throwable primary)throws Throwable {
            this.route=route;this.primary=primary;
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new TransportExtension());env=new Environment(scope,new ByteArrayOutputStream());
            String source=switch(route){
                case FUNCTION->"function transport_target(){return transport_fail();} return 0;";
                case METHOD->"class TransportTarget {static function run(){return transport_fail();}} return 0;";
                case MODULE->"return transport_fail();";
                case CLOSURE->"return function(){return transport_fail();};";
            };
            module=compile(env,debug,dump,source);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            if(route==Route.FUNCTION||route==Route.METHOD)module.include(env);
            if(managed){
                authority=new PhpRuntimeOwnership.Authority(env,domain);execution=authority.enter();
                var nativeMember=TransportFunctions.class.getMethod("transport_fail",Environment.class);
                authority.registerNative(nativeMember,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                    public void validateMember(java.lang.reflect.Method actual){assertEquals(nativeMember,actual);}
                    public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){assertTrue(arguments.isEmpty());}
                });
                switch(route){
                    case FUNCTION->{var target=env.fetchFunction("transport_target");authority.registerFunction(target,target.getData(),(plan,member,bytes)->{});}
                    case METHOD->{var clazz=env.fetchClass("TransportTarget");authority.registerMethod(clazz.findMethod("run"),clazz.getData(),(plan,member,bytes)->{});}
                    case MODULE->authority.registerModule(module,module.getData(),(plan,member,bytes)->{});
                    case CLOSURE->{var entity=module.getClosures().iterator().next();authority.registerModule(module,module.getData(),(plan,member,bytes)->{});authority.registerMethod(entity.findMethod("__invoke"),entity.getData(),(plan,member,bytes)->{});closureResult=authority.include(module);closure=(ObjectMemory)closureResult.raw();}
                }
            }else{
                authority=null;execution=null;
                if(route==Route.CLOSURE)closure=(ObjectMemory)module.include(env);
            }
        }
        Memory invoke()throws Throwable {
            if(authority!=null){
                try(var result=switch(route){
                    case FUNCTION->authority.invoke(env.fetchFunction("transport_target"),TraceInfo.UNKNOWN);
                    case METHOD->authority.invoke(env.fetchClass("TransportTarget").findMethod("run"),null,TraceInfo.UNKNOWN);
                    case MODULE->authority.include(module);
                    case CLOSURE->authority.invoke(closure.value.getReflection().findMethod("__invoke"),closure.value,TraceInfo.UNKNOWN);
                }){return result.raw();}
            }
            return switch(route){
                case FUNCTION->env.fetchFunction("transport_target").invoke(env,TraceInfo.UNKNOWN,new Memory[0]);
                case METHOD->env.fetchClass("TransportTarget").findMethod("run").invokeDynamic(null,env,TraceInfo.UNKNOWN);
                case MODULE->module.include(env);
                case CLOSURE->closure.value.getReflection().findMethod("__invoke").invokeDynamic(closure.value,env,TraceInfo.UNKNOWN);
            };
        }
        @Override public void close()throws Exception {
            CURRENT.remove();if(closureResult!=null)closureResult.close();
            if(authority!=null){authority.close();execution.close();assertEquals(0,domain.graph().liveNodes());}
            try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}
        }
    }
    private ModuleEntity compile(Environment env,boolean debug,boolean dump,String source)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        return module;
    }
    private void mapped(Route route)throws Throwable {
        for(boolean managed:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var primary=new IllegalArgumentException("exact-native-primary");
            try(var fixture=new Fixture(debug,dump,route,managed,primary)){
                CURRENT.set(fixture);Throwable caught=null;try{fixture.invoke();fail("Native failure returned a result");}catch(Throwable failure){caught=failure;}
                assertTrue(caught.toString(),caught instanceof php.runtime.ext.core.classes.WrapJavaExceptions.IllegalArgumentException);
                assertSame(primary,((JavaException)caught).getThrowable());assertEquals(1,fixture.calls);
                if(managed){assertNull(fixture.authority.currentFrame());assertNull(PhpRuntimeOwnership.currentNativeBody(fixture.env));}
            }
        }
    }
    @Test public void functionBoundaryMatchesPassiveTranslationAndPrimaryIdentity()throws Throwable{mapped(Route.FUNCTION);}
    @Test public void methodBoundaryMatchesPassiveTranslationAndPrimaryIdentity()throws Throwable{mapped(Route.METHOD);}
    @Test public void moduleBoundaryMatchesPassiveTranslationAndPrimaryIdentity()throws Throwable{mapped(Route.MODULE);}
    @Test public void closureBoundaryMatchesPassiveTranslationAndPrimaryIdentity()throws Throwable{mapped(Route.CLOSURE);}
    @Test public void jphpFailureIdentityIsPreservedAcrossEveryOrdinaryBoundary()throws Throwable {
        for(Route route:Route.values())for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var primary=new php.runtime.exceptions.CriticalException("exact JPHP primary");
            try(var fixture=new Fixture(debug,dump,route,true,primary)){
                CURRENT.set(fixture);Throwable caught=null;try{fixture.invoke();fail("JPHP failure returned");}catch(Throwable failure){caught=failure;}
                assertSame(primary,caught);assertEquals(1,fixture.calls);assertNull(fixture.authority.currentFrame());
            }
        }
    }
    @Test public void passiveOuterPhpCallerActuallyCatchesTranslatedManagedFailure()throws Throwable {
        for(boolean managed:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var primary=new IllegalArgumentException("outer-catch-primary");
            try(var fixture=new Fixture(debug,dump,Route.FUNCTION,managed,primary)){
                CURRENT.set(fixture);var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.registerExtension(new TransportExtension());
                var caller=new Environment(scope,new ByteArrayOutputStream());
                try{
                    var module=compile(caller,debug,dump,"try {transport_bridge();} catch (\\php\\lang\\IllegalArgumentException $e) {return $e;} return null;");
                    scope.loadModule(module);scope.addUserModule(module);caller.registerModule(module);
                    assertNull(PhpRuntimeOwnership.current(caller));var result=(ObjectMemory)module.include(caller);
                    assertTrue(result.value instanceof php.runtime.ext.core.classes.WrapJavaExceptions.IllegalArgumentException);
                    assertSame(primary,((JavaException)result.value).getThrowable());assertEquals(1,fixture.calls);assertNull(PhpRuntimeOwnership.current(caller));
                }finally{caller.doFinal();}
            }
        }
    }
}
