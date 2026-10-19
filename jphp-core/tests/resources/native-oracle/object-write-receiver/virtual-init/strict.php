<?php
declare(strict_types=1);
$events=[];
class InitProbe {
 public int $g {get {global $events;$events[]='GET';return 1;}}
 public int $s {set {global $events;$events[]='SET';}}
 public int $gs {get {global $events;$events[]='GETSET';return 2;}set {global $events;$events[]='SETGET';}}
 public mixed $ref {&get {global $events;$events[]='REF';return $this->back;}}
 public int $throw {get {global $events;$events[]='THROW';throw new Error('must not execute');}}
 public int $backed {get=> $this->backed;}
 public int $plain;
 public static int $stat;
 public mixed $back=null;
}
class InitChild extends InitProbe {}
function caught($fn){try{return ['ok',$fn()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
$v=new InitProbe;$rows=[];
foreach(['g','s','gs','ref','throw','backed','plain'] as $name){$r=new ReflectionProperty(InitProbe::class,$name);$rows[]=[$name,$r->isVirtual(),caught(fn()=> $r->isInitialized($v)),caught(fn()=> $r->isInitialized(new InitChild)),caught(fn()=> $r->isInitialized(new stdClass)),caught(fn()=> $r->isInitialized()),caught(fn()=> $r->isInitialized(null)),caught(fn()=> $r->isInitialized(1))];}
$rows[]=['no-effect',$events];
foreach(['g','s','gs','ref','throw','backed'] as $name){$r=new ReflectionProperty(InitProbe::class,$name);$rows[]=['unset-'.$name,caught(function()use($v,$name){unset($v->$name);}),$r->isInitialized($v)];}
$v->backed=3;$v->plain=4;$rows[]=['ordinary-init',(new ReflectionProperty(InitProbe::class,'backed'))->isInitialized($v),(new ReflectionProperty(InitProbe::class,'plain'))->isInitialized($v)];unset($v->plain);$rows[]=['ordinary-unset',(new ReflectionProperty(InitProbe::class,'plain'))->isInitialized($v)];
$r=new ReflectionProperty(InitProbe::class,'stat');$rows[]=['static-uninit',$r->isInitialized()];InitProbe::$stat=1;$rows[]=['static-init',$r->isInitialized()];$rows[]=['final-no-effect',$events];
echo json_encode($rows,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
