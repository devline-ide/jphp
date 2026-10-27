package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.memory.LongMemory;

import java.io.OutputStream;

import static org.junit.Assert.*;

/** Public reflected-member mutation retains its reflection access/receiver contract. */
public class MethodInvocationEntryTest extends JvmCompilerCase {
    private static Memory hidden(Environment env, Memory[] args) { return args[0]; }

    @Test public void mutableAccessAndIncompatibleReceiversKeepReflectionErrors() throws Throwable {
        var scope=newScope();scope.setDebugMode(false);
        var env=new Environment(scope,OutputStream.nullOutputStream());
        try {
            var context=new Context("class HandleFixture {public static function id($v){return $v;}public function value($v){return $v;}}return 0;");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            var clazz=env.fetchClass("HandleFixture",false,PhpLanguageProfile.PHP_8_5);
            var method=clazz.findMethod("id");
            var member=MethodInvocationEntryTest.class.getDeclaredMethod("hidden",Environment.class,Memory[].class);
            method.setNativeMethod(member);
            assertEquals(7,method.invokeStatic(env,TraceInfo.UNKNOWN,LongMemory.valueOf(7)).toLong());
            member.setAccessible(false);
            assertThrows(IllegalAccessException.class,()->method.invokeStatic(env,TraceInfo.UNKNOWN,LongMemory.valueOf(8)));
            member.setAccessible(true);
            assertEquals(9,method.invokeStatic(env,TraceInfo.UNKNOWN,LongMemory.valueOf(9)).toLong());
            var dynamic=clazz.findMethod("value");
            assertThrows(IllegalArgumentException.class,()->dynamic.invokeDynamic(new BaseObject(env,clazz){},env,TraceInfo.UNKNOWN,LongMemory.valueOf(3)));
        } finally {env.doFinal();}
    }
}
