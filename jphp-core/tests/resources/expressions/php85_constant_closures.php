<?php
function cc_capture($label,$fn){$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=[$n,$m];return true;});$value=null;try{$value=$fn();}catch(Throwable $e){$events[]=[get_class($e),$e->getMessage()];}restore_error_handler();return [$label,$value,$events];}
class CcBase {
 private const SEED=7;
 const ADD=static function($v=0):int{return self::SEED+$v;};
 const SECOND=static function($v=0):int{return self::SEED+$v;};
 const STATE=static function(){static $n=0;return ++$n;};
 const REFERENCE=static function(&$v):int{$v+=self::SEED;return $v;};
 const ARRAY=["x"=>static function(){return self::SEED;}];
 public $fn=static function(){return [self::SEED,__CLASS__,__TRAIT__];};
 public static $sfn=static function(){return [self::SEED,__CLASS__];};
 const THIS=static function(){return $this;};
 const PROBES=static function(){return [isset($this),empty($this),$this??"none",isset($this->x),empty($this->x),$this->x??"none"];};
}
class CcChild extends CcBase {private const SEED=20;}
trait CcTrait {
 const FN=static function(){return [self::SEED,__CLASS__,__TRAIT__,get_called_class()];};
 const STATE=static function(){static $n=0;return ++$n;};
 public $fn=static function(){return [self::SEED,__CLASS__,__TRAIT__];};
 public static $sfn=static function(){return [self::SEED,__CLASS__];};
}
class CcTraitA {private const SEED=1;use CcTrait;}
class CcTraitB {private const SEED=2;use CcTrait;}
interface CcInterface {const FN=static function(){return self::class;};}
class CcImplement implements CcInterface {}
$rows=[];
foreach(["CcBase","CcChild"]as $c){
 $rows[]=cc_capture([$c,"call"],function()use($c){return [($c::ADD)(3),($c::SECOND)(4),($c::ARRAY["x"])()];});
 $rows[]=cc_capture([$c,"identity"],function()use($c){return [$c::ADD===$c::ADD,$c::ADD===(new ReflectionClassConstant($c,"ADD"))->getValue()];});
 $rows[]=cc_capture([$c,"scope"],function()use($c){$r=new ReflectionFunction($c::ADD);return [$r->isStatic(),$r->getClosureThis(),$r->getClosureScopeClass()->getName(),get_class($c::ADD)];});
 $rows[]=cc_capture([$c,"reference"],function()use($c){$v=3;return [($c::REFERENCE)($v),$v];});
 $rows[]=cc_capture([$c,"defaults"],function()use($c){$a=new $c;$b=new $c;return [($a->fn)(),($c::$sfn)(),$a->fn===$b->fn];});
 $rows[]=cc_capture([$c,"this"],function()use($c){return ($c::THIS)();});
 $rows[]=cc_capture([$c,"quiet-this"],function()use($c){return ($c::PROBES)();});
 $rows[]=cc_capture([$c,"state"],function()use($c){return [($c::STATE)(),($c::STATE)()];});
}
foreach(["CcTraitA","CcTraitB"]as $c){
 $rows[]=cc_capture([$c,"trait"],function()use($c){$o=new $c;return [($c::FN)(),($o->fn)(),($c::$sfn)()];});
 $rows[]=cc_capture([$c,"scope"],function()use($c){$r=new ReflectionFunction($c::FN);return [$r->isStatic(),$r->getClosureScopeClass()->getName()];});
 $rows[]=cc_capture([$c,"state"],function()use($c){return [($c::STATE)(),($c::STATE)()];});
}
foreach(["CcInterface","CcImplement"]as $c)$rows[]=cc_capture([$c,"interface"],function()use($c){return [($c::FN)(),(new ReflectionFunction($c::FN))->getClosureScopeClass()->getName()];});
$rows[]=cc_capture("rebind",function(){$f=CcTraitA::FN;$g=$f->bindTo(null,"CcTraitB");return [$f(),$g(),$f()];});
$rows[]=cc_capture("defaults-cow",function(){$a=(new ReflectionClass("CcBase"))->getDefaultProperties();$fn=$a["fn"];return [$fn(),$fn===(new CcBase)->fn];});
$rows[]=cc_capture("nested-closure",function(){$f=CcBase::ADD;$g=static function()use($f){return $f(10);};return $g();});
return $rows;
