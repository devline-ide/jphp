<?php
error_reporting(-1);$events=[];set_error_handler(function($n,$s)use(&$events){$events[]='warn:'.$n.':'.$s;return true;});class StaticUnsetProbe{public static $x=7;}$o=new StaticUnsetProbe;$events[]='before-unset';try{unset($o->x);$events[]='after-unset';}catch(Throwable $e){$events[]=$e::class.':'.$e->getMessage();}$events[]='static:'.StaticUnsetProbe::$x;echo serialize($events);
