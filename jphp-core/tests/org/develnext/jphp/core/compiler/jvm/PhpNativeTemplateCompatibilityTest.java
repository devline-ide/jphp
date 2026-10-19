package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.support.Extension;
import php.runtime.lang.BaseObject;
import php.runtime.lang.IObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.*;
import static org.junit.Assert.*;

public class PhpNativeTemplateCompatibilityTest extends JvmCompilerCase {
    @Name("UntypedCompileCallbacks") public static class UntypedCompileCallbacks extends BaseObject {
        public UntypedCompileCallbacks(Environment env,ClassEntity entity){super(env,entity);}
        @Signature public Memory onReady(){return Memory.NULL;}
        @Signature(@Arg(value="seconds",type=HintType.DOUBLE)) public Memory onTick(Environment env,Memory... args){return Memory.NULL;}
        @Signature(@Arg(value="input",type=HintType.ARRAY)) public Memory onInput(Environment env,Memory... args){return Memory.NULL;}
    }
    @Name("TypedReturnCallbacks") @NativePhpSignature public static class TypedReturnCallbacks extends BaseObject {
        public TypedReturnCallbacks(Environment env,ClassEntity entity){super(env,entity);}
        @Signature(result=@Arg(type=HintType.INT)) public Memory onReady(Environment env,Memory... args){return Memory.CONST_INT_1;}
    }
    private Environment environment(ByteArrayOutputStream output,PhpLanguageProfile profile,Class<?> nativeClass){
        var scope=newScope();scope.setPhpLanguageProfile(profile);
        scope.registerExtension(new Extension(){
            @Override public Status getStatus(){return Status.EXPERIMENTAL;}
            @Override @SuppressWarnings({"unchecked","rawtypes"}) public void onRegister(CompileScope target){registerClass(target,(Class)nativeClass);}
        });return new Environment(scope,output);
    }
    private ModuleEntity compile(Environment env,String code,boolean dump) throws Throwable {
        var context=new Context(code);context.setPhpLanguageProfile(env.scope.getPhpLanguageProfile());
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,false);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        return module;
    }
    private void publish(Environment env,ModuleEntity module) throws Throwable {
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
    }
    private void positive(Class<?> nativeClass,String phpParent,boolean typedProperties,PhpLanguageProfile profile,boolean dump) throws Throwable {
        var output=new ByteArrayOutputStream();var env=environment(output,profile,nativeClass);
        try {
            var parent=env.fetchClass(phpParent,false,profile);assertNotNull(parent);
            var ready=parent.findMethod("onready");assertTrue(ready instanceof CompileMethodEntity);assertNull(ready.getNativeMethod());assertNull(ready.getReturnTypeChecker());
            assertSame(nativeClass,parent.getNativeClass());assertNull(parent.getModule());assertEquals(0,ready.getParameters().length);
            var tick=parent.findMethod("ontick");assertNotNull(tick.getNativeMethod());assertEquals("float",tick.getParameters()[0].getTypeChecker().getSignature());assertNull(tick.getReturnTypeChecker());
            var input=parent.findMethod("oninput");assertEquals("array",input.getParameters()[0].getTypeChecker().getSignature());assertNull(input.getReturnTypeChecker());
            String declarations=typedProperties ? "private float $x=1.5; private bool $ready=false;" : "private $x=1.5; private $ready=false;";
            var module=compile(env,"class CallbackChild extends \\"+phpParent+" {"+declarations+" public function onReady():void{$this->ready=true;echo $this->x,'|ready';} public function onTick(float $seconds):void{echo '|tick:',$seconds;} public function onInput(array $input):void{echo '|input:',$input['x'];}} $c=new CallbackChild; $c->onReady(); $c->onTick(0.25); $c->onInput(['x'=>7]);",dump);
            assertSame(profile,module.getPhpLanguageProfile());publish(env,module);
            assertEquals("1.5|ready|tick:0.25|input:7",output.toString(StandardCharsets.UTF_8));
            var child=env.fetchClass("CallbackChild",false,profile);assertSame(parent,child.getParent());assertEquals("void",child.findMethod("onready").getReturnTypeChecker().getSignature());
            if(typedProperties){assertEquals("float",child.findProperty("x").getDeclaredType().getSignature());assertEquals("bool",child.findProperty("ready").getDeclaredType().getSignature());}
        }finally{env.doFinal();}
    }
    @Test public void compileNativeUntypedReturnAcceptsTypedVoidChildSourceAndPhbBothProfiles() throws Throwable {
        for(var profile:PhpLanguageProfile.values())for(boolean dump:new boolean[]{false,true})positive(UntypedCompileCallbacks.class,"UntypedCompileCallbacks",false,profile,dump);
    }
    @Test public void actualPrivateGameNativeParentAndTypedPrivateFieldsExecuteSourceAndPhb() throws Throwable {
        Class<?> nativeClass=Class.forName("devline.game.php.PhpLevelController");
        assertEquals(Memory.class,nativeClass.getDeclaredMethod("onReady").getReturnType());
        assertEquals(Memory.class,nativeClass.getDeclaredMethod("onTick",Environment.class,Memory[].class).getReturnType());
        for(boolean dump:new boolean[]{false,true})positive(nativeClass,"devline\\game\\LevelController",true,PhpLanguageProfile.PHP_8_5,dump);
        for(boolean dump:new boolean[]{false,true})positive(nativeClass,"devline\\game\\LevelController",false,PhpLanguageProfile.JPHP_LEGACY,dump);
    }
    @Test public void nativeParameterContractStillRejectsNarrowedStringBeforeBody() throws Throwable {
        for(var profile:PhpLanguageProfile.values())for(boolean dump:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=environment(output,profile,UntypedCompileCallbacks.class);
            try {
                assertThrows(Throwable.class,()->publish(env,compile(env,"class Bad extends UntypedCompileCallbacks {public function onTick(string $seconds):void{}} echo 'body';",dump)));
                env.flushAll();assertEquals(0,output.size());assertNull(env.getGlobals().getByScalar("entered"));
            }finally{env.doFinal();}
        }
    }
    @Test public void declaredNativeReturnStillRejectsVoidBeforeBodySourceAndPhbBothProfiles() throws Throwable {
        for(var profile:PhpLanguageProfile.values())for(boolean dump:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=environment(output,profile,TypedReturnCallbacks.class);
            try {
                assertEquals("int",env.fetchClass("TypedReturnCallbacks",false,profile).findMethod("onready").getReturnTypeChecker().getSignature());
                assertThrows(Throwable.class,()->publish(env,compile(env,"class Bad extends TypedReturnCallbacks {public function onReady():void{}} echo 'body';",dump)));
                env.flushAll();assertEquals(0,output.size());
            }finally{env.doFinal();}
        }
    }
    @Test public void plainPhpUntypedParentAcceptsVoidChildWithModernTypedPrivateFields() throws Throwable {
        for(boolean dump:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);env.scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            try {var module=compile(env,"class P {function onReady(){}} class C extends P {private float $x=1.5; function onReady():void{echo $this->x;}} (new C)->onReady();",dump);publish(env,module);assertEquals("1.5",output.toString(StandardCharsets.UTF_8));}
            finally{env.doFinal();}
        }
    }
    @Test public void legacyProfileExplicitlyRejectsTypedPrivateFieldsBeforeEffects() throws Throwable {
        for(boolean dump:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);
            assertSame(PhpLanguageProfile.JPHP_LEGACY,env.scope.getPhpLanguageProfile());
            try {
                assertThrows(Throwable.class,()->publish(env,compile(env,"class LegacyTyped {private float $x=1.5;} echo 'body';",dump)));
                env.flushAll();assertEquals(0,output.size());
            }finally{env.doFinal();}
        }
    }
}
