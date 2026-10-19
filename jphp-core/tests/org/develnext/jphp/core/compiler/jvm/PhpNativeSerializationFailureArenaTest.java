package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.invoke.Invoker;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.StringMemory;
import php.runtime.memory.output.serialization.PhpSerialization;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual native BODY failures must retire every TEMP and reject missing protocols before effects. */
public class PhpNativeSerializationFailureArenaTest extends JvmCompilerCase {
    private interface Setup {void apply(State state) throws Throwable;}
    private interface Check {void apply(State state,Memory result,Throwable failure) throws Throwable;}
    private static final class State {
        final PhpRuntimeOwnership.Authority authority;
        final Environment env;
        final ByteArrayOutputStream output;
        int factories,autoloads;
        State(PhpRuntimeOwnership.Authority authority,ByteArrayOutputStream output) {
            this.authority=authority;env=authority.environment();this.output=output;
        }
        void provider(String name) throws Throwable {
            var entity=env.fetchClass(name,false);assertNotNull(entity);
            authority.objects().registerPropertiesProvider(entity,
                    entity.getNativeClass().getConstructor(Environment.class,php.runtime.reflection.ClassEntity.class),
                    (actual,host)->{assertSame(entity,actual);assertSame(env,host);factories++;});
        }
        void autoloader() {
            var callback=StringMemory.valueOf("native_autoload_probe");
            var invoker=Invoker.valueOf(env,TraceInfo.UNKNOWN,callback);assertNotNull(invoker);
            env.registerAutoloader(new SplClassLoader(invoker,callback) {
                @Override public boolean load(Memory... args) {autoloads++;throw new AssertionError("Native parser ran autoload");}
            },true);
        }
    }
    private void modes(String declarations,String wire,Setup setup,Check check) throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
            try {
                var context=new Context(declarations+"function native_autoload_probe($name){return false;}"
                        +"function native_reader(){return unserialize('"+wire+"');}return 0;");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
                    var state=new State(authority,output);setup.apply(state);
                    var member=PhpSerialization.class.getMethod("unserialize",Environment.class,TraceInfo.class,Memory.class);
                    authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol() {
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                        public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){
                            assertEquals(1,arguments.size());assertEquals(PhpRuntimeOwnership.Role.VALUE,arguments.getFirst().role());
                            assertEquals(wire,arguments.getFirst().raw().toString());assertNull(arguments.getFirst().node());
                        }
                    });
                    var function=env.fetchFunction("native_reader");var nativeMethod=function.getNativeMethod();
                    var plan=PhpRuntimeOwnership.readRuntimePlan(function.getData(),nativeMethod.getName(),PhpOwnershipExecution.descriptor(nativeMethod));
                    assertNotNull(plan);assertEquals(plan.unsupported().toString(),Collections.emptyList(),plan.unsupported());
                    authority.registerFunction(function,function.getData(),(p,m,b)->assertEquals(PhpOwnershipExecution.EntryKind.FUNCTION,p.entryKind()));
                    Memory result=null;Throwable failure=null;
                    try(var owned=authority.invoke(function,TraceInfo.UNKNOWN)){result=owned.raw();}
                    catch(Throwable caught){failure=caught;}
                    assertNull(PhpRuntimeOwnership.currentNativeBody(env));
                    domain.collectCycles();assertEquals("debug="+debug+" dumped="+dumped,0,domain.graph().liveNodes());
                    check.apply(state,result,failure);
                    assertEquals(0,state.autoloads);
                }
            }finally{env.doFinal();}
        }
    }
    private static void refused(State state,Memory result,Throwable failure,String message,int factories) throws Throwable {
        assertNull(result);assertNotNull(failure);
        assertTrue(failure.toString(),failure instanceof php.runtime.ext.core.classes.WrapJavaExceptions.IllegalArgumentException);
        var primary=((php.runtime.ext.java.JavaException)failure).getThrowable();
        assertTrue(primary.toString(),primary instanceof IllegalArgumentException);
        assertTrue(primary.toString(),primary.getMessage().contains(message));assertEquals(factories,state.factories);
        state.env.getDefaultBuffer().flush();assertEquals("",state.output.toString(StandardCharsets.UTF_8));
    }
    @Test public void unknownClassRejectsBeforeActualAutoload() throws Throwable {
        modes("","O:13:\"NativeUnknown\":0:{}",State::autoloader,
                (s,r,f)->refused(s,r,f,"Native class autoload",0));
    }
    @Test public void knownClassNeedsRegisteredFactory() throws Throwable {
        modes("","O:8:\"stdClass\":0:{}",s->{assertNotNull(s.env.fetchClass("stdClass",false));s.autoloader();},
                (s,r,f)->refused(s,r,f,"no complete ownership allocation provider",0));
    }
    @Test public void restorationHooksRejectBeforeProviderOrCallbackEffects() throws Throwable {
        for(String hook:new String[]{"__wakeup()","__unserialize(array $data):void","__destruct()"})
            modes("class NativeHook{function "+hook+"{echo 'unexpected-hook';}}","O:10:\"NativeHook\":0:{}",
                    s->s.provider("NativeHook"),(s,r,f)->refused(s,r,f,"Native OBJECT callbacks",0));
    }
    @Test public void declaredDefaultsRejectBeforeFactoryEffects() throws Throwable {
        modes("class NativeDefault{public int $x=1;}","O:13:\"NativeDefault\":0:{}",s->s.provider("NativeDefault"),
                (s,r,f)->refused(s,r,f,"Native declared defaults",0));
    }
    @Test public void customWireRejectsBeforeFactoryEffects() throws Throwable {
        modes("","C:8:\"stdClass\":0:{}",s->s.provider("stdClass"),
                (s,r,f)->refused(s,r,f,"Native custom unserialization",0));
    }
    @Test public void nestedProtocolFailureReleasesRootObjectCycle() throws Throwable {
        modes("","O:8:\"stdClass\":2:{s:4:\"self\";r:1;s:1:\"x\";O:13:\"NativeUnknown\":0:{}}",s->{s.provider("stdClass");s.autoloader();},
                (s,r,f)->refused(s,r,f,"Native class autoload",1));
    }
    @Test public void factoryValidationFailurePreservesPrimaryExceptionAndLeavesNoBody() throws Throwable {
        var primary=new IllegalStateException("exact-factory-validation-failure");
        modes("","O:8:\"stdClass\":0:{}",s->{
            var entity=s.env.fetchClass("stdClass",false);
            s.authority.objects().registerPropertiesProvider(entity,
                    entity.getNativeClass().getConstructor(Environment.class,php.runtime.reflection.ClassEntity.class),
                    (actual,host)->{s.factories++;throw primary;});
        },(s,r,f)->{assertNull(r);assertTrue(f instanceof php.runtime.ext.core.classes.WrapJavaExceptions.IllegalStateException);
            assertSame(primary,((php.runtime.ext.java.JavaException)f).getThrowable());assertEquals(1,s.factories);});
    }
    @Test public void malformedObjectGraphReturnsFalseAndRetiresEveryTemp() throws Throwable {
        for(String wire:new String[]{"O:8:\"stdClass\":1:{s:4:\"self\";r:1;",
                "O:8:\"stdClass\":2:{s:4:\"self\";r:1;s:1:\"x\";",
                "O:8:\"stdClass\":1:{s:1:\"a\";a:1:{i:0;R:1;}",
                "O:8:\"stdClass\":1:{s:1:\"a\";r:99;}"})
            modes("",wire,s->s.provider("stdClass"),(s,r,f)->{assertNull(f);assertSame(Memory.FALSE,r);assertEquals(1,s.factories);});
    }
}
