<?php
class DPIncrement {}
function dpi_case($name,$body){$events=[];$value=null;$error=null;set_error_handler(function($s,$m)use(&$events){$events[]=[$s,$m];return true;});try{$value=$body();}catch(Throwable $e){$error=[get_class($e),$e->getMessage()];}finally{restore_error_handler();}return [$name,$value,$error,$events];}
$rows=[];
$rows[]=dpi_case('missing-post-increment',function(){$o=new DPIncrement;$before=$o->x++;return [$before,serialize($o)];});
$rows[]=dpi_case('missing-pre-increment',function(){$o=new DPIncrement;$before=++$o->x;return [$before,serialize($o)];});
$rows[]=dpi_case('missing-post-decrement',function(){$o=new DPIncrement;$before=$o->x--;return [$before,serialize($o)];});
$rows[]=dpi_case('missing-pre-decrement',function(){$o=new DPIncrement;$before=--$o->x;return [$before,serialize($o)];});
return $rows;
