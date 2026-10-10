package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.PhpPropertyWriteContext;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.ObjectMemory;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

public class PhpPropertyGuardContextTest extends JvmCompilerCase {
    private Memory run(Environment env, String code) throws Throwable {return run(env,code,false);}
    private Memory run(Environment env, String code, boolean dumped) throws Throwable {
        Context context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if (dumped) {
            var dumper=new php.runtime.loader.dump.ModuleDumper(context,env,true);
            var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
            module=dumper.load(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
        return module.include(env);
    }

    @Test public void writingEnvironmentOwnsStrictnessAndRestoresNestedContexts() throws Throwable {
        Environment owner=new Environment(newScope(),new ByteArrayOutputStream());
        Environment writer=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var object=run(owner,"class GuardContext {public int $value=1;} return new GuardContext;").toValue(ObjectMemory.class);
            var cell=object.getProperties().getByScalar("value");
            assertNull(PhpPropertyWriteContext.current(null));
            var weak=PhpPropertyWriteContext.enter(owner,TraceInfo.UNKNOWN,false);
            try {
                var strict=PhpPropertyWriteContext.enter(writer,TraceInfo.UNKNOWN,true);
                try {
                    try {cell.assign("2");fail("The writing environment must reject the string");}
                    catch (BaseTypeError expected) {assertEquals(1,cell.toLong());}
                    assertSame(writer,PhpPropertyWriteContext.current(null).environment);
                } finally {PhpPropertyWriteContext.leave(strict);}
                assertSame(weak,PhpPropertyWriteContext.current(null));
                assertEquals(2,cell.assign("2").toLong());
                var internal=PhpPropertyWriteContext.enter(writer,TraceInfo.UNKNOWN,false);
                try {assertEquals(3,cell.assign("3").toLong());}
                finally {PhpPropertyWriteContext.leave(internal);}
                assertSame(weak,PhpPropertyWriteContext.current(null));
            } finally {PhpPropertyWriteContext.leave(weak);}
            assertNull(PhpPropertyWriteContext.current(null));
        } finally {writer.doFinal();owner.doFinal();}
    }

    @Test public void compiledStrictFunctionRestoresContextAfterWeakCallsAndExceptions() throws Throwable {
        Environment env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            run(env,"class GuardContext {public int $value=1;} function guardWeak($o){$o->value='2';} function guardFail($o){$o->value=[];}");
            run(env,"declare(strict_types=1); function guardStrict($o){guardWeak($o);try{guardFail($o);}catch(TypeError $e){} $r=&$o->value;try{$r='3';}catch(TypeError $e){return [$o->value,$e->getMessage()];}return 'wrong';}");
            var result=run(env,"return guardStrict(new GuardContext);");
            assertEquals(2,result.valueOfIndex(0).toLong());
            assertEquals("Cannot assign string to reference held by property GuardContext::$value of type int",result.valueOfIndex(1).toString());
            assertNull(PhpPropertyWriteContext.current(null));
        } finally {env.doFinal();}
    }

    @Test public void clonePreservesShadowedPrivateStorageAndSharedParentSource() throws Throwable {
        for (boolean dumped : new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            try {
                var result=run(env,"class GuardParent {private int $p=1;function parentValue(){return $this->p;}function &parentReference(){return $this->p;}function clearParent(){unset($this->p);}} class GuardChild extends GuardParent {private string $p='child';public array $list=['nested'=>[1]];function childValue(){return $this->p;}} $o=new GuardChild;$r=&$o->parentReference();$c=clone $o;$o->clearParent();try{$r='bad';$message='wrong';}catch(TypeError $e){$message=$e->getMessage();}$c->list['nested'][]=2;return [$c->parentValue(),$c->childValue(),$o->list,$c->list,$message];",dumped);
                assertEquals(1,result.valueOfIndex(0).toLong());assertEquals("child",result.valueOfIndex(1).toString());
                assertEquals(1,result.valueOfIndex(2).valueOfIndex("nested").toValue(php.runtime.memory.ArrayMemory.class).size());
                assertEquals(2,result.valueOfIndex(3).valueOfIndex("nested").toValue(php.runtime.memory.ArrayMemory.class).size());
                assertEquals("Cannot assign string to reference held by property GuardParent::$p of type int",result.valueOfIndex(4).toString());
            } finally {env.doFinal();}
        }
    }

    @Test public void referenceParameterRebindingAndUnsetKeepPropertySource() throws Throwable {
        Environment env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var result=run(env,"class GuardContext {public int $value=1;} function guardRebind(&$p){$local='free';$p=&$local;$p='done';unset($p);} $o=new GuardContext;$r=&$o->value;guardRebind($r);return [$r,$o->value];");
            assertEquals(1,result.valueOfIndex(0).toLong());assertEquals(1,result.valueOfIndex(1).toLong());
            assertNull(PhpPropertyWriteContext.current(null));
        } finally {env.doFinal();}
    }
}
