<?php
class DPDeclared {public $x=1;public static $shared=9;private int $secret=0;protected int $protectedValue=0;}
class DPDestroy {function __destruct(){dp_event(['destructor',array_keys((array)$this)]);}}
function dp_event($event){global $dp_events;$dp_events[]=$event;}
function dp_wire($class,$pairs){$wire='O:'.strlen($class).':"'.$class.'":'.count($pairs).':{';foreach($pairs as $pair)$wire.=serialize($pair[0]).serialize($pair[1]);return $wire.'}';}
function dp_case($label,$body){global $dp_events;$dp_events=[];$result=null;$error=null;set_error_handler(function($severity,$message){dp_event(['warning',$severity,$message]);return true;});try{$result=$body();}catch(Throwable $e){$error=[get_class($e),$e->getMessage()];}finally{restore_error_handler();}return [$label,$result,$error,$dp_events];}
$rows=[];
$rows[]=dp_case('declared-slot-unset-recreate',function(){$o=new DPDeclared;$o->x=2;unset($o->x);$o->x=3;return serialize($o);});
$rows[]=dp_case('throw-unserialize-no-preinsert',function(){set_error_handler(function($s,$m){dp_event(['warning',$s,$m]);throw new Exception('stop');});try{unserialize(dp_wire('DPDestroy',[['x',1]]));}catch(Throwable $e){dp_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return 'done';});
return $rows;
