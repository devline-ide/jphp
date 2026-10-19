package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Observe an actual generated invocation while it is active; no custody registration or Java GC. */
public class PhpPrimitiveCaptureRetirementTest extends JvmCompilerCase {
    private static final class ObservedOutput extends ByteArrayOutputStream {
        final List<Integer> retained=new ArrayList<>();
        final java.lang.reflect.Field current;
        ObservedOutput()throws ReflectiveOperationException {current=PhpPrimitiveCellRuntime.class.getDeclaredField("CURRENT");current.setAccessible(true);}
        @Override public synchronized void write(byte[] bytes,int offset,int length){
            super.write(bytes,offset,length);
            try {
                Object session=((ThreadLocal<?>)current.get(null)).get();
                if(session==null)return;
                var field=session.getClass().getDeclaredField("capturedObjects");field.setAccessible(true);
                Object objects=field.get(session);retained.add(objects==null?0:((Map<?,?>)objects).size());
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        }
    }
    private void check(String body,List<Integer> expected)throws Throwable {
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var output=new ObservedOutput();var env=new Environment(scope,output);env.getDefaultBuffer().setImplicitFlush(true);
            try {
                var context=new Context((strict?"declare(strict_types=1);":"")+body);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                assertTrue("Real module issuer is active",PhpPrimitiveCellPlan.hasClosedMember(module.getNativeClazz(),"__include","(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;Lphp/runtime/memory/ArrayMemory;)Lphp/runtime/Memory;"));
                module.include(env);env.getDefaultBuffer().flush();
                assertEquals("strict="+strict+" debug="+debug+" dump="+dump,expected,output.retained);
                assertEquals("123",output.toString(StandardCharsets.UTF_8));
                assertFalse(PhpPrimitiveCellRuntime.active(env));assertNull(PhpRuntimeOwnership.current(env));
            }finally{env.doFinal();}
        }
    }
    @Test public void finalCapturedCvRetirementRemovesStrongMetadataRoot()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$h=function()use(&$r){return $r;};echo 1;unset($h);echo 2;$h=function()use(&$r){return $r;};echo 3;unset($r,$h);",List.of(1,0,1));
    }
    @Test public void retainedPhysicalObjectCopyKeepsMetadataUntilItsRetirement()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$h=function()use(&$r){return $r;};$u=$h;echo 1;unset($h);echo 2;unset($u);echo 3;unset($r);",List.of(1,1,0));
    }
}
