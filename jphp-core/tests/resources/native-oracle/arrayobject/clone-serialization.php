<?php
require __DIR__.'/common.php';
class AoClone extends ArrayObject {public $p=1;public function __clone(){$this->p++;}}
class AoWireIterator extends ArrayIterator {}
row('clone-array',function(){$a=new ArrayObject(['x'=>1,'n'=>['v'=>1]]);$i=$a->getIterator();$c=clone $a;$c['x']=2;$c['n']['v']=3;return [$a->getArrayCopy(),$c->getArrayCopy(),$i->getArrayCopy()];});
row('clone-object',function(){$o=(object)['x'=>1];$a=new ArrayObject($o);$c=clone $a;$c['x']=2;return [$o->x,$a['x'],$c['x']];});
row('clone-hook',function(){$a=new AoClone(['x'=>1]);$c=clone $a;return [$a->p,$c->p,$c->getArrayCopy()];});
row('serialize-four-fields',function(){$a=new ArrayObject(['x'=>1],2,AoWireIterator::class);$a->p=3;return [$a->__serialize(),$a->serialize()];});
row('serialize-roundtrip',function(){$a=new ArrayObject(['x'=>1],2,AoWireIterator::class);$a->p=3;$b=unserialize(serialize($a));return [get_class($b),$b->getFlags(),$b->getIteratorClass(),$b->getArrayCopy(),get_class($b->getIterator())];});
row('explicit-unserialize',function(){$a=new ArrayObject;$a->__unserialize([3,['y'=>2],['p'=>4],'AoWireIterator']);return [$a->getFlags(),$a->getArrayCopy(),get_object_vars($a),$a->getIteratorClass()];});
row('legacy-wire-method',function(){$a=new ArrayObject(['x'=>1],1);$wire=$a->serialize();$b=new ArrayObject;$b->unserialize($wire);return [$wire,$b->getFlags(),$b->getArrayCopy()];});
row('malformed-state',function(){$a=new ArrayObject;$a->__unserialize([0,[],[]]);return true;});
