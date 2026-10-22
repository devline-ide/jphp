<?php
declare(strict_types=1);
$events=[];
set_error_handler(function($n,$s)use(&$events){$events[]=$n.':'.$s;return true;});
class JsonText {function __toString(): string {global $events;$events[]='toString';return 'null';}}
function row($id,$args) {global $events;json_decode('{');$events=[];$out=null;$exception=null;
 try {$out=json_validate(...$args);}catch(Throwable $e){$exception=[get_class($e),$e->getMessage()];}
 $code=json_last_error();$msg=json_last_error_msg();$ev=$events;
 echo json_encode([$id,$out,$exception,$code,$msg,$ev],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),"\n";
}
row("strict-string-number",[123]);
row("strict-depth-string",['0','2']);
row("strict-null",[null]);
row("strict-stringable",[new JsonText]);
