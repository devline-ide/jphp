package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.Closure;
import php.runtime.memory.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** A real foreign Java thread reads/publishes actual generated PHP addresses; no custody registration. */
public class PhpPrimitiveForeignIngressTest extends JvmCompilerCase {
    private static final class ObservedOutput extends ByteArrayOutputStream {
        Environment env;final int route;boolean captured;Memory retained;
        ObservedOutput(int route){this.route=route;}
        @Override public synchronized void write(byte[] bytes,int offset,int length){
            super.write(bytes,offset,length);if(captured)return;captured=true;
            var result=new AtomicReference<Memory>();var failure=new AtomicReference<Throwable>();
            // Reading the actual Closure on the original thread does not call getUses or manufacture a cell.
            Closure closure=route==0?null:env.getGlobals().getByScalar("h").toObject(Closure.class);
            ReferenceMemory address=route==0?env.getGlobals().getByScalar("r"):null;
            Thread reader=new Thread(()->{
                try{
                    assertFalse("Foreign thread has no sparse Session",PhpPrimitiveCellRuntime.active(env));
                    if(route==0)result.set(address.getRawValue());
                    else if(route==1)result.set(closure.getUses()[0]);
                    else result.set(new ObjectMemory(closure));
                }catch(Throwable error){failure.set(error);}
            },"Primitive-CELL-foreign-QA");
            reader.start();try{reader.join(10000);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            assertFalse("Owned QA reader must finish",reader.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
            retained=result.get();assertNotNull(retained);
        }
    }
    private void check(int route)throws Throwable {
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var output=new ObservedOutput(route);var env=new Environment(scope,output);output.env=env;env.getDefaultBuffer().setImplicitFlush(true);
            try{
                String body="$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;"+(route==0?"":"$h=function()use(&$r){return $r;};")+"echo 1;"+(route==0?"unset($r);":"unset($r,$h);")+"$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t;echo $s;";
                var context=new Context((strict?"declare(strict_types=1);":"")+body);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                assertTrue(PhpPrimitiveCellPlan.hasClosedMember(module.getNativeClazz(),"__include","(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;Lphp/runtime/memory/ArrayMemory;)Lphp/runtime/Memory;"));
                module.include(env);env.getDefaultBuffer().flush();
                assertEquals("route="+route+" strict="+strict+" debug="+debug+" dump="+dump,"199",output.toString(StandardCharsets.UTF_8));
                Memory actual=route==2?output.retained.toObject(Closure.class).__invoke(env):output.retained;
                assertEquals(9,actual.toLong());assertFalse(PhpPrimitiveCellRuntime.active(env));assertNull(PhpRuntimeOwnership.current(env));
            }finally{output.retained=null;env.doFinal();}
        }
    }
    @Test public void foreignRawReadKeepsItsPhysicalCellAliased()throws Throwable{check(0);}
    @Test public void foreignClosureUseArrayPublicationKeepsCapturedCellAliased()throws Throwable{check(1);}
    @Test public void foreignObjectMemoryPublicationKeepsActualClosureCaptureAliased()throws Throwable{check(2);}
}
