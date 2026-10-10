<?php
$dcEvents=[];
function dc_effect($tag,$v){global $dcEvents;$dcEvents[]=$tag;return $v;}
function dc_capture($label,$fn){global $dcEvents;$dcEvents=[];set_error_handler(function($n,$m){global $dcEvents;$dcEvents[]=[$n,$m];return true;});$r=null;try{$r=$fn();}catch(Throwable $e){$dcEvents[]=[get_class($e),$e->getMessage()];}restore_error_handler();return [$label,$r,$dcEvents];}
class DcBase {public const A="base";private const P="base-private";protected const Q="base-protected";public const ARR=[1,[2]];public static $p=41;public static function m($x){return ["method",static::class,$x];}}
class DcChild extends DcBase {public const A="child";private const P="child-private";protected const Q="child-protected";}
class DcOther {public const A="other";}
class DcStringName {public function __toString(){return dc_effect("string-name","A");}}
spl_autoload_register(function($c){dc_effect("autoload:".$c,null);});
$reader=function($c,$n,$mode){
 if($mode===0)return $c::{dc_effect("name",$n)};
 if($mode===1)return DcBase::{dc_effect("name",$n)};
 if($mode===2)return self::{dc_effect("name",$n)};
 if($mode===3)return static::{dc_effect("name",$n)};
 if($mode===4)return parent::{dc_effect("name",$n)};
 if($mode===5)return $c::class;
 if($mode===6)return dc_effect("receiver",$c)::{dc_effect("name",$n)};
 if($mode===7)return (dc_effect("receiver",$c))::class;
 return DcBase::{dc_effect("name","m")}(dc_effect("argument",3));
};
$readers=[$reader,$reader->bindTo(null,"DcBase"),$reader->bindTo(null,"DcChild"),$reader->bindTo(null,"DcOther")];
$classes=["DcBase","DcChild","DcOther","dcbase","\\DcBase","DcMissing",new DcBase,new DcChild,new stdClass,null,5,false,[],3.5];
$names=["A","P","Q","class","CLASS","Class","missing","ARR","",3,null,true,3.5,[],new stdClass,new DcStringName];
$rows=[];
for($pass=0;$pass<2;$pass++)foreach($readers as $i=>$fn)foreach($classes as $j=>$c)foreach($names as $k=>$n)for($mode=0;$mode<9;$mode++){
 if(($mode===1||$mode===2||$mode===3||$mode===4)&&$j!==0)continue;
 if(($mode===5||$mode===7)&&$k!==0)continue;
 if($mode===8&&($j!==0||$k!==0))continue;
 $rows[]=dc_capture([$pass,$i,$j,$k,$mode],function()use($fn,$c,$n,$mode){return $fn($c,$n,$mode);});
}
foreach(["DcBase","DcChild"]as $c)$rows[]=dc_capture(["cow",$c],function()use($c){$n="ARR";$a=$c::{$n};$a[1][0]=99;return [$a,$c::{$n}];});
$rows[]=dc_capture("dynamic-property",function(){$n="p";return [DcBase::${$n},DcBase::$$n];});
$rows[]=dc_capture("constant-offsets",function(){$c="DcBase";$n="ARR";return [DcBase::ARR[0],$c::{$n}[1][0],dc_effect("receiver","DcBase")::ARR[1][0],isset(DcBase::ARR[7]),empty($c::{$n}[1][0])];});
return $rows;
