<?php
require __DIR__.'/common.php';
row('empty',function(){$a=new ArrayObject;return [count($a),$a->getArrayCopy(),$a->getFlags(),$a->getIteratorClass()];});
row('offsets-null-keys',function(){$a=new ArrayObject(['b'=>2,'a'=>1,'n'=>null]);$a[]=3;$a['b']=4;$a[false]=5;return [count($a),$a->offsetExists('n'),isset($a['n']),empty($a['n']),$a['b'],$a->getArrayCopy()];});
row('input-cow-ref',function(){$x=8;$input=['x'=>&$x,'nested'=>['n'=>1]];$a=new ArrayObject($input);$a['x']=9;$a['nested']['n']=2;return [$x,$input,$a->getArrayCopy()];});
row('offset-reference',function(){$a=new ArrayObject(['x'=>1]);$r=&$a['x'];$r=3;$a['x']=4;$a['nested']=['n'=>1];$a['nested']['n']++;return [$r,$a->getArrayCopy()];});
row('object-alias',function(){$o=(object)['x'=>1,'n'=>null];$a=new ArrayObject($o);$a['x']=2;unset($a['n']);$o->z=3;return [$o,$a->getArrayCopy(),count($a)];});
row('exchange-live-iterators',function(){$a=new ArrayObject(['x'=>1]);$i=$a->getIterator();$j=$a->getIterator();$i['x']=2;$old=$a->exchangeArray(['y'=>4]);$j['z']=5;return [$old,$a->getArrayCopy(),$i->getArrayCopy(),$j->getArrayCopy()];});
row('aggregate-backing-detach',function(){$a=new ArrayObject(['x'=>1]);$b=new ArrayObject($a);$a->exchangeArray(['y'=>2]);$follow=$b->getArrayCopy();$b->exchangeArray(['z'=>3]);return [$follow,$a->getArrayCopy(),$b->getArrayCopy()];});
row('offset-bad-key',function(){$a=new ArrayObject;return $a[[]];});
row('invalid-constructor',function(){return new ArrayObject(1);});
row('invalid-exchange',function(){$a=new ArrayObject(['x'=>1]);try{$a->exchangeArray(7);}catch(Throwable $e){return [get_class($e),$e->getMessage(),$a->getArrayCopy()];}});
