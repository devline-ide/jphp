package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;

/** PHP 8.5.11 native contracts; every case runs weak/strict, debug/optimized and source/PHB. */
public class PhpSplArrayIteratorContractTest {
    @Test(timeout = 12000)
    public void emptyAndInitialCursor() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator;echo count($i),':',json_encode([$i->valid(),$i->key(),$i->current(),$i->getFlags(),$i->getArrayCopy()]),';';$i=new ArrayIterator(['x'=>4,9=>5]);echo $i->key(),':',$i->current(),';';$i->next();echo $i->key(),':',$i->current(),';';$i->next();echo json_encode([$i->valid(),$i->key(),$i->current()]),';';$i->rewind();echo $i->key(),':',$i->current(),';';",
                "0:[false,null,null,0,[]];x:4;9:5;[false,null,null];x:4;");
    }

    @Test(timeout = 12000)
    public void seekPositionsAndFailureState() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['a'=>1,'b'=>2,'c'=>3]);$i->seek(2);echo $i->key(),':',$i->current(),';';$i->seek(0);echo $i->key(),';';foreach([-1,3] as $n){try{$i->seek($n);}catch(OutOfBoundsException $e){echo $e->getMessage(),';';}echo json_encode([$i->valid(),$i->key(),$i->current()]),';';}",
                "c:3;a;Seek position -1 is out of range;[true,\"a\",1];Seek position 3 is out of range;[false,null,null];");
    }

    @Test(timeout = 12000)
    public void arrayCopyIsolation() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$a=['a'=>[1], 'b'=>2];$i=new ArrayIterator($a);$a['a'][0]=9;$i['b']=7;$copy=$i->getArrayCopy();$copy['a'][0]=8;$copy['b']=10;echo json_encode([$a,$i->getArrayCopy(),$copy]),';';",
                "[{\"a\":[9],\"b\":2},{\"a\":[1],\"b\":7},{\"a\":[8],\"b\":10}];");
    }

    @Test(timeout = 12000)
    public void referenceReadsAndOffsetWriteDetachment() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$x=1;$a=['x'=>&$x];$i=new ArrayIterator($a);$x=2;echo $i['x'],';';$i['x']=3;echo $x,':',$a['x'],';';$copy=$i->getArrayCopy();$copy['x']=4;echo $x,':',$i['x'],';';",
                "2;2:2;2:3;");
    }

    @Test(timeout = 12000)
    public void objectStorageRemainsShared() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$o=(object)['a'=>1,'b'=>2];$i=new ArrayIterator($o);$o->a=3;$i['b']=4;$i['c']=5;unset($i['a']);echo json_encode([$o,$i->getArrayCopy(),count($i)]),';';",
                "8192:ArrayIterator::__construct(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants;[{\"b\":4,\"c\":5},{\"b\":4,\"c\":5},2];");
    }

    @Test(timeout = 12000)
    public void objectStorageOnlyPublicProperties() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});class S{public $a=1;protected $b=2;private $c=3;public function alter(){$this->a=4;}}$o=new S;$i=new ArrayIterator($o);echo json_encode($i->getArrayCopy()),';';foreach($i as $k=>$v)echo $k,':',$v,';';$o->alter();echo $i['a'],':',count($i),';';",
                "8192:ArrayIterator::__construct(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants;{\"a\":1,\"\\u0000*\\u0000b\":2,\"\\u0000S\\u0000c\":3};a:1;4:1;");
    }

    @Test(timeout = 12000)
    public void appendAndRemovalDuringIteration() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['a'=>1,'b'=>2,'c'=>3]);foreach($i as $k=>$v){echo $k,':',$v,';';if($k==='a'){unset($i['a']);$i['d']=4;}}echo json_encode($i->getArrayCopy()),';';",
                "a:1;c:3;d:4;{\"b\":2,\"c\":3,\"d\":4};");
    }

    @Test(timeout = 12000)
    public void currentRemovalMovesCursor() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['a'=>1,'b'=>2,'c'=>3]);unset($i['a']);echo $i->key(),':',$i->current(),';';$i->next();unset($i['c']);echo json_encode([$i->valid(),$i->key(),$i->current()]),';';$i['d']=4;echo json_encode([$i->valid(),$i->key(),$i->current()]),';';$i->rewind();echo $i->key(),';';",
                "b:2;[false,null,null];[true,\"d\",4];b;");
    }

    @Test(timeout = 12000)
    public void currentReadsLatestStoredValue() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['a'=>1,'b'=>2]);$i['a']=7;echo $i->current(),';';$i->append(3);$i->next();echo $i->current(),';';$i->next();echo $i->key(),':',$i->current(),';';",
                "7;2;0:3;");
    }

    @Test(timeout = 12000)
    public void foreachByReferenceMutatesStorage() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$a=[1,2];$i=new ArrayIterator($a);foreach($i as &$v)$v*=10;unset($v);echo json_encode([$a,$i->getArrayCopy()]),';';foreach($i as &$v){if($v===10)$i[]=30;$v++;}unset($v);echo json_encode($i->getArrayCopy()),';';",
                "[[1,2],[10,20]];[11,21,31];");
    }

    @Test(timeout = 12000)
    public void offsetExistsDistinguishesNullFromIsset() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['n'=>null,'v'=>2]);echo json_encode([$i->offsetExists('n'),isset($i['n']),$i->offsetExists('v'),isset($i['missing']),array_key_exists('n',$i->getArrayCopy())]),';';set_error_handler(function($n,$s){echo $n,':',$s,';';return true;});echo json_encode($i['missing']),';';restore_error_handler();",
                "[true,false,true,false,true];2:Undefined array key \"missing\";null;");
    }

    @Test(timeout = 12000)
    public void offsetAppendAndNumericKeys() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator([5=>'a','x'=>'b']);$i[]=3;$i->offsetSet(null,4);$i['07']=5;$i[true]=6;$i[2.0]=7;echo json_encode($i->getArrayCopy()),';';$i->offsetUnset('x');echo json_encode($i->getArrayCopy()),';';",
                "{\"5\":\"a\",\"x\":\"b\",\"6\":3,\"7\":4,\"07\":5,\"1\":6,\"2\":7};{\"5\":\"a\",\"6\":3,\"7\":4,\"07\":5,\"1\":6,\"2\":7};");
    }

    @Test(timeout = 12000)
    public void invalidOffsetTypes() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator;foreach([[],new stdClass] as $k){foreach(['offsetGet','offsetSet','offsetExists','offsetUnset'] as $m){try{if($m==='offsetSet')$i->$m($k,1);else $i->$m($k);}catch(TypeError $e){echo $m,':',$e->getMessage(),';';}}}",
                "offsetGet:Cannot access offset of type array on ArrayIterator;offsetSet:Cannot access offset of type array on ArrayIterator;offsetExists:Cannot access offset of type array in isset or empty;offsetUnset:Cannot unset offset of type array on ArrayIterator;offsetGet:Cannot access offset of type stdClass on ArrayIterator;offsetSet:Cannot access offset of type stdClass on ArrayIterator;offsetExists:Cannot access offset of type stdClass in isset or empty;offsetUnset:Cannot unset offset of type stdClass on ArrayIterator;");
    }

    @Test(timeout = 12000)
    public void arrayAsPropertiesFlag() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['x'=>1],ArrayIterator::ARRAY_AS_PROPS);echo $i->x,';';$i->x=2;$i->y=3;unset($i->x);echo json_encode([$i->getArrayCopy(),isset($i->x),isset($i->y),$i->getFlags()]),';';$i->setFlags(0);$i->z=4;echo json_encode([$i->getArrayCopy(),get_object_vars($i)]),';';",
                "1;[{\"y\":3},false,true,2];8192:Creation of dynamic property ArrayIterator::$z is deprecated;[{\"y\":3},{\"z\":4}];");
    }

    @Test(timeout = 12000)
    public void standardPropertyListAndArbitraryFlags() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['x'=>1],ArrayIterator::STD_PROP_LIST);$i->own=7;foreach($i as $k=>$v)echo $k,':',$v,';';echo json_encode(get_object_vars($i)),';';$i->setFlags(123);echo $i->getFlags(),';';",
                "8192:Creation of dynamic property ArrayIterator::$own is deprecated;x:1;{\"own\":7};123;");
    }

    @Test(timeout = 12000)
    public void cloneSharesArrayStorageAndStartsAtFirst() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['a'=>1,'b'=>2]);$i->seek(1);$j=clone $i;$j['a']=7;echo json_encode([$i->getArrayCopy(),$j->getArrayCopy(),$i->key(),$j->key()]),';';$j->next();echo json_encode([$i->key(),$j->key()]),';';",
                "[{\"a\":7,\"b\":2},{\"a\":7,\"b\":2},\"b\",\"a\"];[\"b\",\"b\"];");
    }

    @Test(timeout = 12000)
    public void cloneObjectStorageIsShared() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$o=(object)['a'=>1,'b'=>2];$i=new ArrayIterator($o);$j=clone $i;$j['a']=7;echo json_encode([$o,$i->getArrayCopy(),$j->getArrayCopy()]),';';",
                "8192:ArrayIterator::__construct(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants;[{\"a\":7,\"b\":2},{\"a\":7,\"b\":2},{\"a\":7,\"b\":2}];");
    }

    @Test(timeout = 12000)
    public void iteratorConstructorSharesStorage() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['a'=>1]);$j=new ArrayIterator($i);$j['a']=2;$i['b']=3;echo json_encode([$i->getArrayCopy(),$j->getArrayCopy()]),';';",
                "8192:ArrayIterator::__construct(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants;[{\"a\":2,\"b\":3},{\"a\":2,\"b\":3}];");
    }

    @Test(timeout = 12000)
    public void sortsMaintainKeysAndCursorOrdinal() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});foreach(['asort','ksort','natsort','natcasesort'] as $m){$i=new ArrayIterator(['z'=>'a10','b'=>'A2','a'=>'a1']);$i->seek(1);echo $m,':',json_encode($i->$m()),':',json_encode($i->getArrayCopy()),':',$i->key(),';';}$i=new ArrayIterator(['x'=>12,'y'=>3,'z'=>8]);echo json_encode($i->asort(SORT_NUMERIC)),':',json_encode($i->getArrayCopy()),';';",
                "asort:true:{\"b\":\"A2\",\"a\":\"a1\",\"z\":\"a10\"}:a;ksort:true:{\"a\":\"a1\",\"b\":\"A2\",\"z\":\"a10\"}:b;natsort:true:{\"b\":\"A2\",\"a\":\"a1\",\"z\":\"a10\"}:a;natcasesort:true:{\"a\":\"a1\",\"b\":\"A2\",\"z\":\"a10\"}:b;true:{\"y\":3,\"z\":8,\"x\":12};");
    }

    @Test(timeout = 12000)
    public void callbackSortsAndExceptions() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['z'=>1,'b'=>3,'a'=>2]);echo json_encode($i->uasort(function($a,$b){return $b<=>$a;})),':',json_encode($i->getArrayCopy()),';';echo json_encode($i->uksort(function($a,$b){return strcmp($a,$b);})),':',json_encode($i->getArrayCopy()),';';try{$i->uasort(function(){throw new Exception('stop');});}catch(Exception $e){echo $e->getMessage(),';';}echo json_encode($i->getArrayCopy()),';';",
                "true:{\"b\":3,\"a\":2,\"z\":1};true:{\"a\":2,\"b\":3,\"z\":1};stop;{\"a\":2,\"b\":3,\"z\":1};");
    }

    @Test(timeout = 12000)
    public void modernSerializationState() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['x'=>1],3);$i->own=7;$i->next();echo json_encode($i->__serialize()),';';$s=serialize($i);echo $s,';';$j=unserialize($s);echo json_encode([$j->getFlags(),$j->getArrayCopy(),$j->key(),get_object_vars($j)]),';';",
                "[3,{\"x\":1,\"own\":7},[],null];O:13:\"ArrayIterator\":4:{i:0;i:3;i:1;a:2:{s:1:\"x\";i:1;s:3:\"own\";i:7;}i:2;a:0:{}i:3;N;};[3,{\"x\":1,\"own\":7},\"x\",[]];");
    }

    @Test(timeout = 12000)
    public void legacySerializationWireAndRestore() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['x'=>1],1);$i->own=7;$s=$i->serialize();echo $s,';';$j=new ArrayIterator;$j->unserialize($s);echo json_encode([$j->getFlags(),$j->getArrayCopy(),$j->key(),get_object_vars($j)]),';';",
                "8192:Creation of dynamic property ArrayIterator::$own is deprecated;x:i:1;a:1:{s:1:\"x\";i:1;};m:a:1:{s:3:\"own\";i:7;};8192:Creation of dynamic property ArrayIterator::$own is deprecated;[1,{\"x\":1},\"x\",{\"own\":7}];");
    }

    @Test(timeout = 12000)
    public void unserializeValidatesPayload() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['old'=>1]);foreach([[],[0,[],[]],[0,[],[],null],['bad',[],[],null],[0,3,[],null],[0,[],3,null]] as $a){try{$i->__unserialize($a);echo 'ok;';}catch(Throwable $e){echo get_class($e),':',$e->getMessage(),';';}}echo json_encode($i->getArrayCopy()),';';",
                "UnexpectedValueException:Incomplete or ill-typed serialization data;ok;ok;UnexpectedValueException:Incomplete or ill-typed serialization data;InvalidArgumentException:Passed variable is not an array or object;UnexpectedValueException:Incomplete or ill-typed serialization data;[];");
    }

    @Test(timeout = 12000)
    public void invalidConstructorStorage() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});foreach([null,1,'x',true] as $v){try{new ArrayIterator($v);}catch(TypeError $e){echo $e->getMessage(),';';}}",
                "ArrayIterator::__construct(): Argument #1 ($array) must be of type array, null given;ArrayIterator::__construct(): Argument #1 ($array) must be of type array, int given;ArrayIterator::__construct(): Argument #1 ($array) must be of type array, string given;ArrayIterator::__construct(): Argument #1 ($array) must be of type array, true given;");
    }

    @Test(timeout = 12000)
    public void subclassMethodDispatch() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});class Doubled extends ArrayIterator{public function current():mixed{return parent::current()*2;}}$i=new Doubled(['x'=>3,'y'=>4]);foreach($i as $k=>$v)echo $k,':',$v,';';$g=(function()use($i){yield from $i;})();foreach($g as $k=>$v)echo $k,':',$v,';';",
                "x:6;y:8;x:6;y:8;");
    }

    @Test(timeout = 12000)
    public void interfacesConstantsAndMethodInventory() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator;echo json_encode([$i instanceof SeekableIterator,$i instanceof Iterator,$i instanceof Traversable,$i instanceof ArrayAccess,$i instanceof Serializable,$i instanceof Countable,ArrayIterator::STD_PROP_LIST,ArrayIterator::ARRAY_AS_PROPS]),';';$m=get_class_methods($i);sort($m);echo implode(',',$m),';';",
                "[true,true,true,true,true,true,1,2];__construct,__debugInfo,__serialize,__unserialize,append,asort,count,current,getArrayCopy,getFlags,key,ksort,natcasesort,natsort,next,offsetExists,offsetGet,offsetSet,offsetUnset,rewind,seek,serialize,setFlags,uasort,uksort,unserialize,valid;");
    }

    @Test(timeout = 12000)
    public void debugInfoStorageAndProperties() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$i=new ArrayIterator(['a'=>1],1);$i->own=2;echo json_encode($i->__debugInfo()),';';$i->setFlags(2);echo json_encode($i->__debugInfo()),';';",
                "8192:Creation of dynamic property ArrayIterator::$own is deprecated;{\"own\":2,\"\\u0000ArrayIterator\\u0000storage\":{\"a\":1}};{\"own\":2,\"\\u0000ArrayIterator\\u0000storage\":{\"a\":1}};");
    }

    @Test(timeout = 12000)
    public void nestedOffsetsAndExportedReference() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$a=['x'=>[1]];$i=new ArrayIterator($a);$i['x'][0]=2;$r=&$i['x'];$r[0]=3;echo json_encode([$a,$i->getArrayCopy()]),';';$i['x']=[4];echo json_encode([$r,$i->getArrayCopy()]),';';",
                "[{\"x\":[1]},{\"x\":[3]}];[[3],{\"x\":[4]}];");
    }

    @Test(timeout = 12000)
    public void sortRejectsReentrantMutation() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});foreach(['set','unset','append','construct','sort'] as $op){$i=new ArrayIterator(['a'=>3,'b'=>1,'c'=>2]);try{$i->uasort(function($a,$b)use($i,$op){if($op==='set')$i['x']=9;elseif($op==='unset')unset($i['a']);elseif($op==='append')$i->append(9);elseif($op==='construct')$i->__construct([9]);else $i->ksort();return $a<=>$b;});echo 'ok;';}catch(Throwable $e){echo $op,':',get_class($e),':',$e->getMessage(),';';}echo json_encode($i->getArrayCopy()),';';}",
                "set:Error:Modification of ArrayObject during sorting is prohibited;{\"a\":3,\"b\":1,\"c\":2};unset:Error:Modification of ArrayObject during sorting is prohibited;{\"a\":3,\"b\":1,\"c\":2};append:Error:Modification of ArrayObject during sorting is prohibited;{\"a\":3,\"b\":1,\"c\":2};construct:Error:Modification of ArrayObject during sorting is prohibited;{\"a\":3,\"b\":1,\"c\":2};ok;{\"b\":1,\"c\":2,\"a\":3};");
    }

    @Test(timeout = 12000)
    public void malformedLegacyRestoreMatchesNativePartialState() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});foreach(['','x:i:0;','x:i:0;a:0:{}','x:i:0;a:0:{};m:a:0:{};trailing','x:i:0;i:3;;m:a:0:{};'] as $s){$i=new ArrayIterator(['old'=>1],2);try{$i->unserialize($s);echo 'ok;';}catch(Throwable $e){echo get_class($e),':',$e->getMessage(),';';}echo json_encode([$i->getFlags(),$i->getArrayCopy()]),';';}",
                "ok;[2,{\"old\":1}];UnexpectedValueException:Error at offset 6 of 6 bytes;[2,{\"old\":1}];UnexpectedValueException:Error at offset 12 of 12 bytes;[0,[]];ok;[0,[]];UnexpectedValueException:Error at offset 6 of 20 bytes;[2,{\"old\":1}];");
    }

    @Test(timeout = 12000)
    public void referenceIterationThroughSubclass() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});class S extends ArrayIterator{public function current():mixed{return parent::current()+10;}}$i=new S([1,2]);try{foreach($i as &$v){$v++;}}catch(Throwable $e){echo get_class($e),':',$e->getMessage(),';';}echo json_encode($i->getArrayCopy()),';';",
                "Error:An iterator cannot be used with foreach by reference;[1,2];");
    }

    @Test(timeout = 12000)
    public void sortObjectBackingReordersSharedProperties() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$o=(object)['z'=>3,'b'=>1,'a'=>2];$i=new ArrayIterator($o);$i->asort();echo json_encode([$o,$i->getArrayCopy()]),';';",
                "8192:ArrayIterator::__construct(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants;[{\"b\":1,\"a\":2,\"z\":3},{\"b\":1,\"a\":2,\"z\":3}];");
    }

    @Test(timeout = 12000)
    public void modernSerializePreservesObjectIdentity() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("set_error_handler(function($n,$s){echo $n,chr(58),$s,chr(59);return true;});$o=(object)['a'=>1];$i=new ArrayIterator($o,2);echo json_encode($i->__serialize()),';';$j=new ArrayIterator;$j->__unserialize($i->__serialize());$j['a']=2;echo json_encode([$o,$i->getArrayCopy(),$j->getArrayCopy()]),';';",
                "8192:ArrayIterator::__construct(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants;[2,{\"a\":1},[],null];8192:ArrayIterator::__unserialize(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants;[{\"a\":2},{\"a\":2},{\"a\":2}];");
    }

}
