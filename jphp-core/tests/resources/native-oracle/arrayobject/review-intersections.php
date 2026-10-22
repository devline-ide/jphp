<?php
require __DIR__.'/common.php';
row('clone-explicit-cell',function(){$a=new ArrayObject(['x'=>1]);$r=&$a['x'];$c=clone $a;$r=7;return [$a['x'],$c['x']];});
row('clone-self-backed',function(){$a=new ArrayObject;$a->p=1;$a->__construct($a);$c=clone $a;$c['p']=7;return [$a['p'],$a->p,$c['p'],$c->p,$c->__serialize()[0]];});
row('self-legacy-wire',function(){$a=new ArrayObject;$a->p=1;$a->__construct($a);return $a->serialize();});
class ReviewOffsetAO extends ArrayObject {public array $calls=[];public function offsetGet(mixed $k):mixed{$this->calls[]='get';return parent::offsetGet($k);}public function offsetSet(mixed $k,mixed $v):void{$this->calls[]='set';parent::offsetSet($k,$v);}public function offsetExists(mixed $k):bool{$this->calls[]='exists';return parent::offsetExists($k);}public function offsetUnset(mixed $k):void{$this->calls[]='unset';parent::offsetUnset($k);}}
row('property-offset-overrides',function(){$a=new ReviewOffsetAO(['x'=>1],ArrayObject::ARRAY_AS_PROPS);$v=$a->x;$a->x=2;$i=isset($a->x);unset($a->x);return [$v,$i,$a->calls];});
row('self-wire-roundtrip',function(){$a=new ArrayObject;$a->p=1;$a->__construct($a);$wire=$a->serialize();$b=new ArrayObject;$b->unserialize($wire);$b['p']=8;return [$b->getFlags(),$b->p,$b['p'],$b->__serialize()[0],$b->serialize()];});
class ReviewTypedAO extends ArrayObject {public int $x=7;}
class ReviewReadonlyAO extends ArrayObject {public readonly int $x;public function __construct(){parent::__construct();$this->x=7;}}
class ReviewHookAO extends ArrayObject {public int $calls=0;public int $x=7 {get => $this->x;set {$this->calls++;$this->x=$value;}}}
row('typed-own-raw-restore',function(){$a=new ReviewTypedAO;$a->__unserialize([0,[],['x'=>'bad'],null]);return [get_debug_type($a->x),$a->x];});
row('readonly-own-restore',function(){$a=new ReviewReadonlyAO;try{$a->__unserialize([0,[],['x'=>8],null]);return ['accepted',$a->x];}catch(Throwable $e){return [get_class($e),$e->getMessage(),$a->x];}});
row('hook-own-raw-restore',function(){$a=new ReviewHookAO;$a->__unserialize([0,[],['calls'=>0,'x'=>8],null]);return [$a->x,$a->calls];});
row('absent-own-properties-merge',function(){$a=new ReviewTypedAO;$a->p=1;$a->__unserialize([0,[],[],null]);return [$a->x,$a->p,get_object_vars($a)];});
row('readonly-failure-state-order',function(){$a=new ReviewReadonlyAO;$a->exchangeArray(['old'=>1]);$a->setFlags(2);try{$a->__unserialize([1,['new'=>2],['x'=>8],null]);}catch(Throwable $e){return [get_class($e),$e->getMessage(),$a->x,$a->getFlags(),$a->getArrayCopy()];}});
