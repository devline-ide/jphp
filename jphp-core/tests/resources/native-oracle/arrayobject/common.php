<?php
function row($id,$f){$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=[$n,$m];return true;});try{$value=$f();$result=[$id,'ok',$value,$events];}catch(Throwable $e){$result=[$id,'throw',get_class($e),$e->getMessage(),$events];}finally{restore_error_handler();}echo json_encode($result,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),"\n";}
function pairs($o){$result=[];foreach($o as $k=>$v)$result[]=[$k,$v];return $result;}
