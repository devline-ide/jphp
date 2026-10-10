<?php
class DPReentrant {}
function dpr_warm($o){return $o->x;}
function dpr_event($event){global $dpr_events;$dpr_events[]=$event;}
function dpr_handler($o,$throwing=false,$warm=false){set_error_handler(function($s,$m)use($o,$throwing,$warm){dpr_event(['warning',$s,$m]);dpr_event(['outer-sees',property_exists($o,'x')]);set_error_handler(function($s,$m){dpr_event(['warning',$s,$m]);return true;});try{$o->x=4;}finally{restore_error_handler();}if($warm){global $dpr_alias;$dpr_alias[0]=&$o->x;dpr_event(['warm-inner',dpr_warm($o),serialize((array)$o),serialize($o)]);}if($throwing)throw new Exception('stop');return true;});}
function dpr_snapshot($o){return [$o->x,serialize($o),serialize((array)$o),serialize(get_object_vars($o)),count((array)$o)];}
function dpr_case($name,$body){global $dpr_events;$dpr_events=[];$value=null;$error=null;set_error_handler(function($s,$m){dpr_event(['warning',$s,$m]);return true;});try{$value=$body();}catch(Throwable $e){$error=[get_class($e),$e->getMessage()];}finally{restore_error_handler();}return [$name,$value,$error,$dpr_events];}
$rows=[];
$rows[]=dpr_case('setter-native-duplicate-buckets',function(){$o=new DPReentrant;dpr_handler($o);try{$o->x=1;}finally{restore_error_handler();}return dpr_snapshot($o);});
$rows[]=dpr_case('throwing-setter-native-duplicate-buckets',function(){$o=new DPReentrant;dpr_handler($o,true);try{$o->x=1;}catch(Throwable $e){dpr_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return dpr_snapshot($o);});
$rows[]=dpr_case('reference-reuses-inner-cell',function(){$o=new DPReentrant;dpr_handler($o);try{$r=&$o->x;}finally{restore_error_handler();}$before=dpr_snapshot($o);$r=9;return [$before,dpr_snapshot($o),$r];});
$rows[]=dpr_case('throwing-reference-reuses-inner-cell',function(){$o=new DPReentrant;dpr_handler($o,true);try{$r=&$o->x;}catch(Throwable $e){dpr_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return dpr_snapshot($o);});
$rows[]=dpr_case('reference-unset-exposes-earlier-bucket',function(){$o=new DPReentrant;dpr_handler($o);try{$o->x=1;}finally{restore_error_handler();}$r=&$o->x;$r=9;$before=dpr_snapshot($o);unset($o->x);return [$before,property_exists($o,'x'),$o->x??'missing',serialize($o),$r];});
$rows[]=dpr_case('warm-cache-and-alias-retain-inner-bucket',function(){global $dpr_alias;$o=new DPReentrant;dpr_handler($o,false,true);try{$o->x=1;}finally{restore_error_handler();}$before=[dpr_warm($o),$o->x,serialize($o),serialize((array)$o),serialize(get_object_vars($o))];$dpr_alias[0]=6;return [$before,[dpr_warm($o),$o->x,serialize($o)],$dpr_alias[0]];});
$rows[]=dpr_case('throw-warm-cache-and-alias-retain-inner-bucket',function(){global $dpr_alias;$o=new DPReentrant;dpr_handler($o,true,true);try{$o->x=1;}catch(Throwable $e){dpr_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}$before=[dpr_warm($o),$o->x,serialize($o)];$dpr_alias[0]=6;return [$before,[dpr_warm($o),$o->x,serialize($o)],$dpr_alias[0]];});
return $rows;
