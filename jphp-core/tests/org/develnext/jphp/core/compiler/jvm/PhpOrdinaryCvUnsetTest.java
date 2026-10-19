package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ReferenceMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Real ordinary compiler CV unlinking, independently of sparse CELL admission or Java GC. */
public class PhpOrdinaryCvUnsetTest extends JvmCompilerCase {
    private void check(String body,String expected)throws Throwable {
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context((strict?"declare(strict_types=1);":"")+body);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.getDefaultBuffer().flush();
                assertEquals("strict="+strict+" debug="+debug+" dumped="+dumped,expected,output.toString(StandardCharsets.UTF_8));
                assertNull(PhpRuntimeOwnership.current(env));
            }finally{env.doFinal();}
        }
    }
    @Test public void scalarAliasRetained()throws Throwable {check("$a=1;$b=&$a;unset($a);$b=7;echo $b;","7");}
    @Test public void objectAliasRetained()throws Throwable {check("class T{public $v=1;}$a=new T;$b=&$a;unset($a);$b->v=7;echo $b->v;","7");}
    @Test public void prealiasedTypedObjectKeepsGuard()throws Throwable {check("class T{public int $v=1;}$o=null;$alias=&$o;$o=new T;$r=&$o->v;unset($o);try{$r=\"free\";echo \"bad\";}catch(TypeError $e){echo \"T\";}echo $alias->v;","T1");}
    @Test public void scalarPropertyAliasRetained()throws Throwable {check("class T{public int $v=1;}$o=new T;$a=&$o->v;$b=&$a;unset($a);$b=7;echo $o->v;","7");}
    @Test public void objectPropertyAliasRetained()throws Throwable {check("class T{public $v=1;}class Box{public $v;}$box=new Box;$box->v=new T;$a=&$box->v;$b=&$a;unset($a);$b->v=7;echo $box->v->v;","7");}
    @Test public void capturedObjectAliasRetained()throws Throwable {check("class T{public $v=1;}$a=new T;$f=function()use(&$a){return $a;};unset($a);echo $f()->v;","1");}
    @Test public void referenceArgumentUnlinksOnlyCalleeCv()throws Throwable {check("class T{public $v=1;}function drop(&$x){unset($x);}$a=new T;drop($a);echo $a->v;","1");}
    @Test public void staticBorrowUnlinksOnlyCv()throws Throwable {check("class T{public $v=1;}$f=function($drop){static $x;if($x===null)$x=new T;if($drop){unset($x);return 0;}return $x->v;};$f(true);echo $f(false);","1");}
    @Test public void globalBindingUnlinksOnlyCv()throws Throwable {check("class T{public $v=1;}$a=new T;function drop(){global $a;unset($a);}drop();echo $a->v;","1");}
    @Test public void arrayStoredAliasRetained()throws Throwable {check("class T{public $v=1;}$a=new T;$items=[&$a];unset($a);echo $items[0]->v;","1");}
    @Test public void propertyUnsetPreservesSeparateObjectCell()throws Throwable {check("class T{public $v=1;}class Box{public $v;}$box=new Box;$box->v=new T;$r=&$box->v;unset($box->v);echo $r->v;","1");}
    @Test public void typedPropertyUnsetRemovesOnlyItsGuard()throws Throwable {check("class T{public int $v=1;}$o=new T;$r=&$o->v;unset($o->v);$r=\"free\";echo $r;","free");}
    @Test public void arrayElementUnsetPreservesSeparateCell()throws Throwable {check("class T{public $v=1;}$items=[new T];$r=&$items[0];unset($items[0]);echo $r->v;","1");}
    @Test public void unsharedObjectKeepsManualDestructorRoute()throws Throwable {check("class D{public function __destruct(){echo \"D\";}}$a=new D;unset($a);echo 1;","D1");}
    @Test public void retainedReferenceDoesNotDestructLiveObject()throws Throwable {check("$events=0;class D{public $v=1;public function __destruct(){$GLOBALS[\"events\"]+=1;}}$a=new D;$b=&$a;unset($a);echo $events,$b->v;","01");}
    @Test public void magicPropertyUnsetStillDispatches()throws Throwable {check("class M{public function __unset($name){echo $name;}}$m=new M;unset($m->missing);echo 1;","missing1");}
    @Test public void unknownCarrierRetainsItsVirtualManualUnset() {
        final class UnknownCarrier extends ReferenceMemory {
            int manualCalls;
            UnknownCarrier(Memory value){super(value);}
            @Override public void manualUnset(Environment env){manualCalls++;}
        }
        var carrier=new UnknownCarrier(ReferenceMemory.exportReference(new ReferenceMemory(Memory.CONST_INT_1)));
        PhpRuntimeOwnership.unsetLocal(null,0,carrier,null);
        assertEquals(1,carrier.manualCalls);assertEquals(1,carrier.toLong());
    }
}
