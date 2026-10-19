package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/** Native PHP 8.5 delegation preserves suspension, child position and bidirectional messages. */
public class PhpGeneratorDelegationTest extends JvmCompilerCase {
    @Override
    protected php.runtime.env.CompileScope newScope() {
        var scope = super.newScope();
        scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
        return scope;
    }

    void checkDelegation(String source, String expected) throws Throwable {
        for (boolean strict : new boolean[]{false, true})
            for (boolean debug : new boolean[]{false, true})
                for (boolean dumped : new boolean[]{false, true}) {
                    var scope = newScope();
                    scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    scope.setDebugMode(debug);
                    var output = new ByteArrayOutputStream();
                    var env = new Environment(scope, output);
                    try {
                        var context = new Context((strict ? "declare(strict_types=1);" : "") + source);
                        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module = new JvmCompiler(env, context).compile(false);
                        if (dumped) {
                            var dumper = new ModuleDumper(context, env, true);
                            var bytes = new ByteArrayOutputStream();
                            dumper.save(module, bytes);
                            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                        module.include(env);
                        env.doFinal();
                        assertEquals("strict=" + strict + " debug=" + debug + " dumped=" + dumped,
                                expected, output.toString(StandardCharsets.UTF_8));
                    } finally {
                        env.doFinal();
                    }
                }
    }

    @Test(timeout = 12000)
    public void nestedValid() throws Throwable {
        checkDelegation("function leaf($a){foreach($a as $v)yield $v;}function middle(){foreach([[1,2,3],[4,5,6],[7,8,9]] as $a){$g=leaf($a);if($g->valid())yield from $g;}}function outer(){yield from middle();}foreach(outer() as $v)echo $v,',';", "1,2,3,4,5,6,7,8,9,");
    }

    @Test(timeout = 12000)
    public void nestedNext() throws Throwable {
        checkDelegation("function leaf($a){foreach($a as $v)yield $v;}function middle(){foreach([[1,2,3],[4,5,6],[7,8,9]] as $a){$g=leaf($a);$g->next();yield from $g;}}function outer(){yield from middle();}foreach(outer() as $v)echo $v,',';", "2,3,5,6,8,9,");
    }

    @Test(timeout = 12000)
    public void lazyEffects() throws Throwable {
        checkDelegation("function leaf(){echo 'A';yield 1;echo 'B';yield 2;echo 'C';return 9;}function outer(){return yield from leaf();}$g=outer();$g->rewind();echo ':',$g->current(),';';$g->next();echo ':',$g->current(),';';$g->next();echo ':',$g->getReturn(),';';", "A:1;B:2;C:9;");
    }

    @Test(timeout = 12000)
    public void childPosition() throws Throwable {
        checkDelegation("function leaf(){yield 1;yield 2;}$child=leaf();$g=(function()use($child){yield from $child;})();$g->rewind();echo $g->current(),':',$child->current(),';';$g->next();echo $g->current(),':',$child->current(),';';", "1:1;2:2;");
    }

    @Test(timeout = 12000)
    public void forwardSend() throws Throwable {
        checkDelegation("function leaf(){$x=yield 1;echo 'got:',$x,';';return 'done';}function middle(){return yield from leaf();}function outer(){return yield from middle();}$g=outer();echo $g->current(),';';$g->send(7);echo $g->getReturn(),';';", "1;got:7;done;");
    }

    @Test(timeout = 12000)
    public void forwardThrow() throws Throwable {
        checkDelegation("function leaf(){try{yield 1;}catch(Exception $e){echo 'caught:',$e->getMessage(),';';yield 2;}return 3;}function outer(){return yield from leaf();}$g=outer();echo $g->current(),';';echo $g->throw(new Exception('x')),';';$g->next();echo $g->getReturn(),';';", "1;caught:x;2;3;");
    }

    @Test(timeout = 12000)
    public void arrayReturn() throws Throwable {
        checkDelegation("function outer(){$r=yield from [5,6];echo is_null($r)?'null;':'other;';return 9;}$g=outer();foreach($g as $v)echo $v,',';echo $g->getReturn(),';';", "5,6,null;9;");
    }

    @Test(timeout = 12000)
    public void delegatedKeys() throws Throwable {
        checkDelegation("function outer(){yield 'first'=>'a';yield from [10=>'x','str'=>'y',2=>'z'];yield 'last';}foreach(outer() as $k=>$v)echo $k,':',$v,';';", "first:a;10:x;str:y;2:z;0:last;");
    }

    @Test(timeout = 12000)
    public void nextSendsNull() throws Throwable {
        checkDelegation("function leaf(){$x=yield 4;echo is_null($x)?'null;':'other;';yield 5;}$g=leaf();echo $g->current(),';';$g->next();echo $g->current(),';';", "4;null;5;");
    }

    @Test(timeout = 12000)
    public void closureArgumentsAndCaptures() throws Throwable {
        checkDelegation("$x=3;$y=4;$f=function($a=5)use($x,&$y){yield $a+$x+$y;$y++;return func_num_args();};$g=$f(2,'extra');$x=90;$y=6;$g->rewind();echo $g->current(),';';$g->next();echo $y,':',$g->getReturn(),';';", "11;7:2;");
    }

    @Test(timeout = 12000)
    public void closureDefaultAndCaptures() throws Throwable {
        checkDelegation("$x=3;$f=function($a=5)use($x){yield $a+$x;return func_num_args();};$g=$f();$x=90;$g->rewind();echo $g->current(),';';$g->next();echo $g->getReturn(),';';", "8;0;");
    }

    @Test(timeout = 12000)
    public void emptyValidity() throws Throwable {
        checkDelegation("function emptyLeaf(){if(false)yield 0;}function outer(){yield from emptyLeaf();yield from [];return 8;}$g=emptyLeaf();echo $g->valid()?'bad;':'empty;';$g=outer();echo $g->valid()?'bad;':'empty;';echo $g->getReturn(),';';", "empty;empty;8;");
    }

    @Test(timeout = 12000)
    public void iteratorDelegation() throws Throwable {
        checkDelegation("class NativeDelegationIterator implements Iterator{private $i=0;public function rewind():void{$this->i=0;}public function valid():bool{return $this->i<2;}public function current():mixed{return $this->i+4;}public function key():mixed{return $this->i===0?'x':9;}public function next():void{++$this->i;}}function outer(){$r=yield from new NativeDelegationIterator();echo is_null($r)?'null;':'other;';}foreach(outer() as $k=>$v)echo $k,':',$v,';';", "x:4;9:5;null;");
    }

    @Test(timeout = 12000)
    public void returnAvailability() throws Throwable {
        checkDelegation("function leaf(){yield 1;}$g=leaf();try{$g->getReturn();echo 'bad;';}catch(Exception $e){echo 'before:',$e->getMessage(),';';}$g->rewind();try{$g->getReturn();echo 'bad;';}catch(Exception $e){echo 'paused:',$e->getMessage(),';';}$g->next();echo is_null($g->getReturn())?'done:null;':'bad;';function broken(){yield 1;throw new Exception('fail');}$g=broken();$g->rewind();try{$g->next();}catch(Exception $e){}try{$g->getReturn();echo 'bad;';}catch(Exception $e){echo 'failed:',$e->getMessage(),';';}", "before:Cannot get return value of a generator that hasn't returned;paused:Cannot get return value of a generator that hasn't returned;done:null;failed:Cannot get return value of a generator that hasn't returned;");
    }
}
