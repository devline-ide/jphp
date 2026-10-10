<?php
function cc_capture($label,$fn){$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=[$n,$m];return true;});$result=null;try{$result=$fn();}catch(Throwable $e){$events[]=[get_class($e),$e->getMessage()];}restore_error_handler();return [$label,$result,$events];}
class CcBase {
 public const PUB="base";private const PRIV="base-private";protected const PROT="base-protected";
 public const U=[1,[2]];public const u=[3,[4]];
 public static function closure(){return function($c,$kind){
  if($kind===0)return $c::PUB;
  if($kind===1)return $c::PRIV;
  if($kind===2)return $c::PROT;
  if($kind===3)return $c::pub;
  if($kind===4)return $c::MISSING;
  if($kind===5)return self::class;
  if($kind===6)return static::class;
  if($kind===7)return parent::class;
  if($kind===8)return self::PRIV;
  if($kind===9)return static::PRIV;
  if($kind===10)return self::PROT;
  if($kind===11)return static::PROT;
  if($kind===12)return $c::U;
  return $c::u;
 };}
}
class CcChild extends CcBase {public const PUB="child";private const PRIV="child-private";protected const PROT="child-protected";public const U=[5,[6]];public const u=[7,[8]];}
class CcGrand extends CcChild {}
class CcOther {public const PUB="other";private const PRIV="other-private";protected const PROT="other-protected";public const U=[9];public const u=[10];}
class CcInit extends CcBase {private const SEED="seed";public const U=[self::SEED];public const u=[self::SEED,"lower"];public const SELF_NAME=self::class;public const PARENT_NAME=parent::class;public static $p=[self::SEED];}
trait CcInitTrait {public static $t=[self::SEED];}
class CcInitTraitUser {use CcInitTrait;private const SEED="trait-user";}
$global=function($c,$kind){
 if($kind===0)return $c::PUB;
 if($kind===1)return $c::PRIV;
 if($kind===2)return $c::PROT;
 if($kind===3)return $c::pub;
 if($kind===4)return $c::MISSING;
 if($kind===5)return self::class;
 if($kind===6)return static::class;
 if($kind===7)return parent::class;
 if($kind===8)return self::PRIV;
 if($kind===9)return static::PRIV;
 if($kind===10)return self::PROT;
 if($kind===11)return static::PROT;
 if($kind===12)return $c::U;
 return $c::u;
};
$base=CcBase::closure();
$readers=[$global,$global->bindTo(null,"CcBase"),$global->bindTo(null,"CcChild"),$global->bindTo(null,"CcGrand"),$global->bindTo(null,"CcOther"),$base,$base->bindTo(null,"CcChild"),$base->bindTo(null,null),$base->bindTo(new CcChild,null)];
$classes=["CcBase","CcChild","CcGrand","CcOther","ccbase","\\CcChild","CcMissing",new CcBase,new CcChild,new CcOther,new stdClass,"",null,7,3.5,true,[]];
$rows=[];
for($pass=0;$pass<2;$pass++)foreach($readers as $i=>$reader)foreach($classes as $j=>$c)for($kind=0;$kind<14;$kind++){
 $rows[]=cc_capture([$pass,$i,$j,$kind],function()use($reader,$c,$kind){return $reader($c,$kind);});
}
foreach(["CcBase","CcChild","CcOther"]as $c){
 $rows[]=cc_capture(["cow",$c],function()use($c){$a=$c::U;$a[0]=99;$a[1][0]=88;return [$a,$c::U,$c::u];});
}
$rows[]=cc_capture("class-initializers",function(){return [CcInit::U,CcInit::u,CcInit::SELF_NAME,CcInit::PARENT_NAME,CcInit::$p];});
$rows[]=cc_capture("trait-initializer",function(){return CcInitTraitUser::$t;});
return $rows;
