<?php
class PSTPlain {public $x=1;public $y=2;}
class PSTTyped {public int $x=1;public int $y=2;public int $z;}
class PSTParent {private $p=1;protected $q=2;function clear(){unset($this->p,$this->q);}function fill(){ $this->p=3;$this->q=4;}function &p(){return $this->p;}}
class PSTChild extends PSTParent {public $x=5;}
class PSTMagic {public $x=1;public int $t=2;public $y=3;function __isset($n){pst_event(['isset',$n]);return true;}function __get($n){pst_event(['get',$n]);return 7;}function __set($n,$v){pst_event(['set',$n,$v]);}function __unset($n){pst_event(['unset',$n]);}}
class PSTDuplicate {}
function pst_event($v){global $pst_events;$pst_events[]=$v;}
function pst_case($name,$body){global $pst_events;$pst_events=[];set_error_handler(function($s,$m){pst_event([$s,$m]);return true;});try{$v=$body();$e=null;}catch(Throwable $x){$v=null;$e=[get_class($x),$x->getMessage()];}finally{restore_error_handler();}return [$name,$v,$e,$pst_events];}
function pst_views($o){$v=[];foreach($o as $k=>$x){$v[]=[$k,$x];}return [serialize($o),serialize((array)$o),serialize(get_object_vars($o)),$v];}
function pst_duplicate(){ $o=new PSTDuplicate;set_error_handler(function()use($o){set_error_handler(function(){return true;});try{$o->x=4;}finally{restore_error_handler();}return true;});try{$o->x=1;}finally{restore_error_handler();}return $o;}
$rows=[];
$rows[]=pst_case('unset-untyped-views',function(){$o=new PSTPlain;unset($o->x);return [pst_views($o),property_exists($o,'x'),isset($o->x)];});
$rows[]=pst_case('recreate-untyped-order',function(){$o=new PSTPlain;unset($o->x);$o->x=3;return pst_views($o);});
$rows[]=pst_case('reference-untyped-null-and-detach',function(){$o=new PSTPlain;$a=&$o->x;unset($o->x);$b=&$o->x;$before=pst_views($o);$b=3;unset($o->x);return [$a,$b,$before,pst_views($o)];});
$rows[]=pst_case('unset-typed-views',function(){$o=new PSTTyped;unset($o->x);return [pst_views($o),property_exists($o,'x'),isset($o->x)];});
$rows[]=pst_case('recreate-typed-order',function(){$o=new PSTTyped;unset($o->x);$o->x=3;return pst_views($o);});
$rows[]=pst_case('typed-reference-detach-guard',function(){$o=new PSTTyped;$a=&$o->x;unset($o->x);$a='free';$o->x=3;try{$o->x='bad';}catch(Throwable $e){pst_event([get_class($e),$e->getMessage()]);}return [$a,pst_views($o)];});
$rows[]=pst_case('typed-uninitialized-read',function(){$o=new PSTTyped;return $o->z;});
$rows[]=pst_case('typed-explicit-unset-read',function(){$o=new PSTTyped;unset($o->x);return $o->x;});
$rows[]=pst_case('private-protected-recreate',function(){$o=new PSTChild;$o->clear();$before=pst_views($o);$o->fill();return [$before,pst_views($o),property_exists($o,'p'),property_exists($o,'q')];});
$rows[]=pst_case('magic-unset-untyped',function(){$o=new PSTMagic;unset($o->x);$a=isset($o->x);$b=empty($o->x);$c=$o->x;unset($o->x);$o->x=9;return [$a,$b,$c,pst_views($o)];});
$rows[]=pst_case('magic-unset-typed',function(){$o=new PSTMagic;unset($o->t);return [$o->t,pst_views($o)];});
$rows[]=pst_case('reflection-unset-private',function(){$o=new PSTChild;$o->clear();$p=new ReflectionProperty(PSTParent::class,'p');return [$p->isInitialized($o),$p->getValue($o),pst_views($o)];});
$rows[]=pst_case('reflection-magic-untyped',function(){$o=new PSTMagic;unset($o->x);$p=new ReflectionProperty(PSTMagic::class,'x');return [$p->getValue($o),$p->isInitialized($o),pst_views($o)];});
$rows[]=pst_case('clone-unset-anchors',function(){$o=new PSTPlain;unset($o->x);$c=clone $o;$c->x=8;$o->x=3;return [pst_views($o),pst_views($c)];});
$rows[]=pst_case('clone-unset-typed',function(){$o=new PSTTyped;unset($o->x);$c=clone $o;$c->x=8;return [pst_views($o),pst_views($c)];});
$rows[]=pst_case('duplicates-clone',function(){$o=pst_duplicate();$c=clone $o;return [pst_views($o),pst_views($c)];});
$rows[]=pst_case('duplicates-cast-cow',function(){$o=pst_duplicate();$a=(array)$o;$b=$a;$a['x']=9;return [serialize($a),serialize($b),serialize($o)];});
$rows[]=pst_case('duplicates-cast-unset',function(){$o=pst_duplicate();$a=(array)$o;unset($a['x']);return [serialize($a),$a['x'],serialize($o)];});
$rows[]=pst_case('duplicates-values-and-merge',function(){$o=pst_duplicate();$a=(array)$o;return [array_values($a),serialize(array_merge([],$a)),serialize($a+[])];});
$rows[]=pst_case('literal-numeric-property',function(){$o=new PSTDuplicate;$o->{'0'}=7;return [$o->{'0'},property_exists($o,'0'),serialize($o),serialize((array)$o)];});
$rows[]=pst_case('duplicates-merge-nonempty-left',function(){$o=pst_duplicate();return serialize(array_merge(['z'=>0],(array)$o));});
$rows[]=pst_case('duplicates-merge-three-args',function(){$o=pst_duplicate();return serialize(array_merge([],(array)$o,[]));});
$rows[]=pst_case('merge-numeric-string-keys',function(){return serialize(array_merge([],[2=>10,'x'=>20]));});
$rows[]=pst_case('union-other-physical-value',function(){$o=pst_duplicate();return [serialize([]+(array)$o),serialize(['z'=>0]+(array)$o)];});
return $rows;
