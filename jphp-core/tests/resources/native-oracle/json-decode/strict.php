<?php
declare(strict_types=1);
$events=[];
set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s];return true;});
function row($id,$args){global $events;
 json_decode('{');$events=[];$v=null;$failure=null;
 try{$v=json_decode(...$args);}catch(Throwable $e){$failure=[get_class($e),$e->getMessage(),$e->getCode()];}
 $err=json_last_error();$msg=json_last_error_msg();
 echo json_encode([$id,base64_encode(serialize($v)),$err,$msg,$events,$failure]),"\n";
}
row("strict-int-json",[123]);
row("strict-null-json",[null]);
row("strict-bool-assoc",['{}',1]);
row("strict-null-assoc",['{}',null]);
row("strict-float-depth",['0',null,2.0]);
row("strict-string-flags",['0',null,512,'2']);
row("strict-array-flags",['0',null,512,[]]);
row("strict-valid-four",['9223372036854775808',true,512,JSON_BIGINT_AS_STRING]);
