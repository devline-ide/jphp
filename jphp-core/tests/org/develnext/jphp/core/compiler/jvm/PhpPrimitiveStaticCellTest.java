package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.lang.Closure;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Real source and PHB callers; no test-only custody registration or forced Java GC. */
public class PhpPrimitiveStaticCellTest extends JvmCompilerCase {
    private static Memory javaRetained;
    public static final class EscapeExtension extends Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new EscapeFunctions());}
    }
    public static final class EscapeFunctions extends FunctionsContainer {
        public static Memory primitive_java_retain(Environment env,Memory callable)throws Throwable {
            javaRetained=callable.toObject(Closure.class).__invoke(env);javaRetained.assign(5);return Memory.NULL;
        }
        public static Memory primitive_java_peek(){return javaRetained.toImmutable();}
    }
    private void check(String body,String expected,boolean javaEscape)throws Throwable {
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            if(javaEscape)scope.registerExtension(new EscapeExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context((strict?"declare(strict_types=1);":"")+body);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.getDefaultBuffer().flush();
                assertEquals("strict="+strict+" debug="+debug+" dump="+dump,expected,output.toString(StandardCharsets.UTF_8));
                assertNull("Normal run never enables whole graph ownership",PhpRuntimeOwnership.current(env));
                assertFalse("Outer generated return retired sparse custody",PhpPrimitiveCellRuntime.active(env));
            }finally{javaRetained=null;env.doFinal();}
        }
    }
    @Test public void native_released_source_and_dump()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;","5,9",false);}
    @Test public void native_retained_cv_source_and_dump()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s,\",\",$r;","9,9,9",false);}
    @Test public void native_rebound_cv_source_and_dump()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$other=40;$r=&$other;$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s,\",\",$r;","5,9,40",false);}
    @Test public void native_retained_array_source_and_dump()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$a=[&$r];unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s,\",\",$a[0];","9,9,9",false);}
    @Test public void native_retained_field_source_and_dump()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$o=new stdClass;$o->v=&$r;unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s,\",\",$o->v;","9,9,9",false);}
    @Test public void native_retained_capture_source_and_dump()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$capture=function()use(&$r){return $r;};unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s,\",\",$capture();","9,9,9",false);}
    @Test public void native_dynamic_byref_retained_source_and_dump()throws Throwable {check("function &relay($f){return $f();}$f=function &(){static $x=0;return $x;};$fn=\"relay\";$r=&$fn($f);$r=5;$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s,\",\",$r;","9,9,9",false);}
    @Test public void nativeJavaBareHolderBeforeFirstPromotionRemainsAliased()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};primitive_java_retain($f);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s,',',primitive_java_peek();","9,9,9",true);
    }
    @Test public void bothPhysicalCvOwnersMustRetireBeforeClone()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$u=&$r;unset($r);unset($u);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;","5,9",false);
    }
    @Test public void secondPhysicalCvOwnerPreservesAlias()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$u=&$r;unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s,',',$u;","9,9,9",false);
    }
    @Test public void completedByReferenceSendRetiresItsActualPacket()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;function borrow(&$v){}borrow($r);unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;","5,9",false);
    }
    @Test public void physicalArgumentCvWritesTheOriginalCellAndRetires()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;function borrow(&$v){$v=6;}borrow($r);unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;","6,9",false);
    }
    @Test public void completedSendDoesNotRetireAnotherCallerCv()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$u=&$r;function borrow(&$v){}borrow($r);unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s,',',$u;","9,9,9",false);
    }
    @Test public void capturedClosureReleased()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};unset($r,$h);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;","5,9",false);}
    @Test public void lastCapturedClosureCopyReleased()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};$u=$h;unset($r,$h,$u);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;","5,9",false);}
    @Test public void retainedCapturedClosureCopyPreservesCell()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};$u=$h;unset($r,$h);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;echo \",\",$u();","9,9,9",false);}
    @Test public void capturedClosureCvReboundRetiresUse()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};unset($r);$h=40;$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;echo \",\",$h;","5,9,40",false);}
    @Test public void twoPhysicalCapturedObjectsMustRetire()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};$u=function()use(&$r){return $r;};unset($r,$h,$u);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;","5,9",false);}
    @Test public void onePhysicalCapturedObjectRetainedPreservesCell()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};$u=function()use(&$r){return $r;};unset($r,$h);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;echo \",\",$u();","9,9,9",false);}
    @Test public void untrackedCapturedObjectReferencePreservesAlias()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};$u=&$h;unset($r,$h);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;echo \",\",$u();","9,9,9",false);}
    @Test public void retainedOriginalCvAfterCapturedObjectRelease()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};unset($h);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;echo \",\",$r;","9,9,9",false);}
    @Test public void twoUseSlotsReleasedTogether()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$u=&$r;$h=function()use(&$r,&$u){return $r;};unset($r,$u,$h);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,\",\",$s;","5,9",false);}
    @Test public void twoExternalCapturedObjectReferencesPreserveLastAlias()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};$u=&$h;$v=&$h;unset($r,$h,$u);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;echo ',',$v();","9,9,9",false);
    }
    @Test public void capturedObjectValueCopySurvivesReferenceSourceUnset()throws Throwable {
        check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$h=function()use(&$r){return $r;};$u=&$h;$v=$h;unset($r,$h,$u);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;echo ',',$v();","9,9,9",false);
    }
    @Test public void nestedByReferenceInvocationRetiresBothPackets()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;function second(&$w){$w=6;}function borrow(&$v){second($v);}borrow($r);unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;","6,9",false);}
    @Test public void recursiveByReferenceInvocationRestoresOuterCustody()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;function borrow(&$v,&$n){if($n){$n=0;borrow($v,$n);}else{$v=6;}}$n=1;borrow($r,$n);unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;","6,9",false);}
    @Test public void ordinaryNestedInvocationDoesNotRetireCallerAlias()throws Throwable {check("$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;$u=&$r;function leaf(){return 1;}function borrow(&$v){leaf();}borrow($r);unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s,',',$u;","9,9,9",false);}
}
