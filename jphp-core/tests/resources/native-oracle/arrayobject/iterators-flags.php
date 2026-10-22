<?php
require __DIR__.'/common.php';
class AoCustomIterator extends ArrayIterator {public static $constructs=0;public function __construct($array=[],$flags=0){self::$constructs++;parent::__construct($array,$flags);}public function current():mixed{return parent::current()*10;}}
foreach([0,1,2,3] as $flags)row('flags-'.$flags,function()use($flags){$a=new ArrayObject(['x'=>1],$flags);$a->p=2;return [$a->getArrayCopy(),(array)$a,get_object_vars($a),pairs($a),json_encode($a),$a->getIterator()->getFlags()];});
row('custom-constructor-bypass',function(){$a=new ArrayObject(['a'=>1,'b'=>2],3,AoCustomIterator::class);$i=$a->getIterator();$i->next();$j=$a->getIterator();$a->setFlags(0);return [get_class($i),$i->key(),$j->key(),pairs($a),AoCustomIterator::$constructs,$i->getFlags(),$a->getFlags()];});
row('foreach-reference',function(){$a=new ArrayObject(['a'=>1,'b'=>2]);foreach($a as &$value)$value*=2;unset($value);return $a->getArrayCopy();});
row('set-iterator-class',function(){$a=new ArrayObject(['x'=>1]);$a->setIteratorClass('\\AoCustomIterator');return [$a->getIteratorClass(),pairs($a),AoCustomIterator::$constructs];});
row('invalid-iterator',function(){return new ArrayObject([],0,'stdClass');});
row('invalid-missing-iterator',function(){$a=new ArrayObject;$a->setIteratorClass('NoSuchIterator');});
row('sort',function(){$a=new ArrayObject(['z'=>10,'a'=>2,'b'=>1]);$i=$a->getIterator();$v=[$a->asort(SORT_NUMERIC),$a->getArrayCopy(),$i->getArrayCopy(),$a->ksort(),$a->getArrayCopy()];$a->natsort();$a->natcasesort();$a->uasort(function($a,$b){return $b<=>$a;});$v[]=$a->getArrayCopy();$a->uksort(function($a,$b){return $b<=>$a;});$v[]=$a->getArrayCopy();return $v;});
row('sort-write-rejected',function(){$a=new ArrayObject([2,1]);try{$a->uasort(function($x,$y)use($a){$a[]=3;return $x<=>$y;});}catch(Throwable $e){return [get_class($e),$e->getMessage(),count($a)];}});
