<?php
function lc_capture($label,$fn){$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=[$n,$m];return true;});$result=null;try{$result=$fn();}catch(Throwable $e){$events[]=[get_class($e),$e->getMessage()];}restore_error_handler();return [$label,$result,$events];}
class LcForward {public const A=self::B+1;public const B=self::C+1;public const C=4;public const ITEMS=[self::A,[self::B,self::C]];}
class LcAcross {public const X=LcLater::Y+1;}
class LcLater {public const Y=LcForward::A+1;}
interface LcInterface {public const X=LcLater::Y+2;}
class LcImplement implements LcInterface {}
class LcUndefined {public const GOOD=1;public const BAD=LC_MISSING;public static function noop(){return 9;}}
class LcCycle {public const A=self::B;public const B=self::A;public const GOOD=5;}
class LcCycleA {public const X=LcCycleB::Y;}
class LcCycleB {public const Y=LcCycleA::X;}
class LcArrayCycle {public const X=[self::Y];public const Y=[self::X];}
class LcArithmetic {public const GOOD=2;public const BAD=1/0;public const MOD=2%0;}
class LcParent {private const SEED=4;public const X=self::SEED+2;public const WHO=self::class;}
class LcChild extends LcParent {private const SEED=8;}
trait LcTrait {public const X=self::SEED+2;public const WHO=self::class;public const PAIR=[self::X,self::WHO];}
class LcTraitA {private const SEED=10;use LcTrait;}
class LcTraitB {private const SEED=20;use LcTrait;}
class LcProps {public const GOOD=3;public static $a=self::GOOD;public $b=[self::GOOD];}
class LcPropsBadConstant {public const GOOD=1;public const BAD=LC_MISSING;public static $a=7;public $b=8;}
class LcPropsBadProperty {public const GOOD=3;public static $a=LC_PROP_MISSING;public $b=self::GOOD;}
trait LcPropsTrait {public static $ts=[self::SEED];public $x=self::SEED;}
class LcPropsTraitA {private const SEED=1;use LcPropsTrait;}
class LcPropsTraitB {private const SEED=2;use LcPropsTrait;}
$rows=[];
$rows[]=lc_capture("declared",function(){return [class_exists("LcUndefined"),class_exists("LcCycle"),interface_exists("LcInterface")];});
for($pass=0;$pass<2;$pass++)foreach(["LcForward","LcAcross","LcLater","LcInterface","LcImplement","LcUndefined","LcCycle","LcCycleA","LcCycleB","LcArrayCycle","LcArithmetic","LcParent","LcChild","LcTraitA","LcTraitB"]as $c)
 foreach(["A","B","C","X","Y","GOOD","BAD","MOD","ITEMS","WHO","PAIR"]as $name){
  $rows[]=lc_capture([$pass,$c,$name,"fetch"],function()use($c,$name){return $c::{$name};});
  $rows[]=lc_capture([$pass,$c,$name,"reflection"],function()use($c,$name){return (new ReflectionClassConstant($c,$name))->getValue();});
 }
foreach(["LcUndefined","LcCycle","LcArithmetic","LcForward","LcAcross","LcParent","LcChild","LcTraitA","LcTraitB"]as $c){
 $rows[]=lc_capture([$c,"new"],function()use($c){$o=new $c;return get_class($o);});
 $rows[]=lc_capture([$c,"reflection-all"],function()use($c){return (new ReflectionClass($c))->getConstants();});
}
foreach(["LcProps","LcPropsBadConstant","LcPropsBadProperty","LcPropsTraitA","LcPropsTraitB"]as $c){
 $rows[]=lc_capture([$c,"property-declaration"],function()use($c){return [class_exists($c),$c::GOOD];});
 $rows[]=lc_capture([$c,"defaults"],function()use($c){return (new ReflectionClass($c))->getDefaultProperties();});
 $rows[]=lc_capture([$c,"property-new"],function()use($c){$o=new $c;return $c==="LcPropsTraitA"||$c==="LcPropsTraitB"?$o->x:$o->b;});
 $rows[]=lc_capture([$c,"property-static"],function()use($c){if($c==="LcPropsTraitA"||$c==="LcPropsTraitB")return $c::$ts;return $c::$a;});
}
$rows[]=lc_capture("defaults-snapshot",function(){LcProps::$a=99;return [LcProps::$a,(new ReflectionClass("LcProps"))->getDefaultProperties(),(new LcProps)->b];});
define("LC_PROP_MISSING",21);
$rows[]=lc_capture("property-retry",function(){return [LcPropsBadProperty::$a,(new LcPropsBadProperty)->b];});
$rows[]=lc_capture("noop",function(){return LcUndefined::noop();});
$rows[]=lc_capture("repair-first",function(){return LcUndefined::BAD;});
define("LC_MISSING",17);
$rows[]=lc_capture("repair-second",function(){return LcUndefined::BAD;});
$rows[]=lc_capture("repair-third",function(){return LcUndefined::BAD;});
$rows[]=lc_capture("repair-new",function(){return get_class(new LcUndefined);});
$rows[]=lc_capture("array-cow",function(){$a=LcForward::ITEMS;$a[1][0]=99;return [$a,LcForward::ITEMS];});
$rows[]=lc_capture("trait-direct",function(){return [(new LcPropsTraitA)->x,(new LcPropsTraitB)->x];});
$rows[]=lc_capture("ternary-scalar",function(){$c="LcPropsTraitA";return [$c==="LcPropsTraitA"||$c==="LcPropsTraitB"?1:2,$c==="LcPropsTraitA"||$c==="LcPropsTraitB"?2:1];});
class LcMacros {const GOOD=3;const F=__FUNCTION__;const M=__METHOD__;const VALUES=[__FUNCTION__,self::GOOD,__METHOD__,__CLASS__,__TRAIT__];public $x=[__FUNCTION__,self::GOOD,__METHOD__];}
trait LcMacroTrait {const F=__FUNCTION__;const M=__METHOD__;const VALUES=[__FUNCTION__,self::SEED,__METHOD__,__CLASS__,__TRAIT__];public $x=[__FUNCTION__,self::SEED,__METHOD__,__CLASS__,__TRAIT__];}
class LcMacroA {const SEED=1;use LcMacroTrait;}
class LcMacroB {const SEED=2;use LcMacroTrait;}
foreach(["LcMacros","LcMacroA","LcMacroB"]as $c)$rows[]=lc_capture([$c,"macros"],function()use($c){return [$c::F,$c::M,$c::VALUES,(new $c)->x];});
$rows[]=lc_capture("defaults-cow",function(){$a=(new ReflectionClass("LcProps"))->getDefaultProperties();$a["b"][0]=999;return [$a,(new ReflectionClass("LcProps"))->getDefaultProperties(),(new LcProps)->b];});
foreach([false,true]as $a)foreach([false,true]as $b)foreach([false,true]as $c)$rows[]=lc_capture([$a,$b,$c,"logic"],function()use($a,$b,$c){$events=[];$f=function($v)use(&$events){$events[]=$v;return $v;};return [$a||$b?11:22,$a&&$b||$c,$a||$b&&$c,$a&&$b?11:22,$a||$b??11,$f($a)||$f($b)?$f(11):$f(22),$events];});
foreach(["LcCycle","LcArithmetic","LcForward","LcInterface","LcMacros"]as $c){
$rows[]=lc_capture([$c,"defaults-empty"],function()use($c){return (new ReflectionClass($c))->getDefaultProperties();});
$rows[]=lc_capture([$c,"new-without-constructor"],function()use($c){return get_class((new ReflectionClass($c))->newInstanceWithoutConstructor());});
}
return $rows;
