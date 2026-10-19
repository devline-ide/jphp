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

/** iterator_count acquires a value iterator and only advances/validates its positions. */
public class PhpSplIteratorCountContractTest extends JvmCompilerCase {
    private void output(String source, String expected) throws Throwable {
        for (var profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean strict : new boolean[]{false, true})
                for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                    var scope = super.newScope(); scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
                    var out = new ByteArrayOutputStream(); var env = new Environment(scope, out);
                    try {
                        String program = strict ? source.replace("<?php", "<?php declare(strict_types=1);") : source;
                        var context = new Context(program.startsWith("<?php") ? program.substring(5) : program); context.setPhpLanguageProfile(profile);
                        var module = new JvmCompiler(env, context).compile(false);
                        if (phb) {
                            var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
                            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                        module.include(env); env.doFinal();
                        assertEquals(profile + " strict=" + strict + " debug=" + debug + " PHB=" + phb,
                                expected, out.toString(StandardCharsets.UTF_8));
                    } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
                }
    }

    @Test(timeout = 60000) public void valueAndReferenceGeneratorsSourceAndDump() throws Throwable {
        output("<?php function values(){yield 1;yield 2;}function &refs(&$x){yield $x;yield $x;}$x=7;echo iterator_count(values()),':',iterator_count(refs($x)),':',$x;", "2:2:7");
    }

    @Test(timeout = 60000) public void countableIsNotCountAndNoValueOrKeyFetchSourceAndDump() throws Throwable {
        output("<?php class CountProbe implements Iterator,Countable{public $i=0;public $events='';function rewind():void{$this->events.='r';$this->i=0;}function next():void{$this->events.='n';++$this->i;}function valid():bool{$this->events.='v';return $this->i<2;}function current():mixed{throw new Exception('current called');}function key():mixed{throw new Exception('key called');}function count():int{throw new Exception('count called');}}$p=new CountProbe;echo iterator_count($p),':',$p->i,':',$p->events;", "2:2:rvnvnv");
    }

    @Test(timeout = 60000) public void nativeArrayiteratorCountConsumesWithoutVirtualValueFetchSourceAndDump() throws Throwable {
        output("<?php class NativeCountProbe extends ArrayIterator{function current():mixed{throw new Exception('current called');}function key():mixed{throw new Exception('key called');}function count():int{throw new Exception('count called');}}$p=new NativeCountProbe([3,4]);echo iterator_count($p),':',($p->valid()?'live':'end');", "2:end");
    }

    @Test(timeout = 60000) public void iteratoraggregateUsesOneRealIteratorSourceAndDump() throws Throwable {
        output("<?php class CountProbe implements Iterator,Countable{public $i=0;public $events='';function rewind():void{$this->events.='r';$this->i=0;}function next():void{$this->events.='n';++$this->i;}function valid():bool{$this->events.='v';return $this->i<2;}function current():mixed{throw new Exception('current called');}function key():mixed{throw new Exception('key called');}function count():int{throw new Exception('count called');}}class CountAggregate implements IteratorAggregate{public $calls=0;public $p;function __construct(){$this->p=new CountProbe;}function getIterator():Traversable{++$this->calls;return $this->p;}}$a=new CountAggregate;echo iterator_count($a),':',$a->calls,':',$a->p->events;", "2:1:rvnvnv");
    }

    @Test(timeout = 60000) public void foreachReferenceGuardIsPreservedSourceAndDump() throws Throwable {
        output("<?php function values(){yield 1;yield 2;}echo iterator_count(values()),':';try{foreach(values() as &$v){}echo 'unguarded';}catch(Throwable $e){echo 'guard';}", "2:guard");
    }

    @Test(timeout = 60000) public void traversalFailurePreservesPrimaryAndOrderSourceAndDump() throws Throwable {
        output("<?php class FailingCount implements Iterator{public $i=0;function rewind():void{echo 'r';}function next():void{echo 'n';++$this->i;}function valid():bool{echo 'v';if($this->i===1)throw new Exception('stop');return true;}function current():mixed{throw new Exception('wrong current');}function key():mixed{throw new Exception('wrong key');}}try{iterator_count(new FailingCount);}catch(Throwable $e){echo ':',$e->getMessage();}", "rvnv:stop");
    }

}
