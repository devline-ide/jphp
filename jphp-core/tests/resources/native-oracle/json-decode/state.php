<?php
$events=[];
set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s];return true;});
class DecodeText {function __toString():string {global $events;$events[]='toString';return 'null';}}
function row($id,$args){global $events;
 json_decode(hex2bin('22ff22'));$before=json_last_error();$events=[];$v=null;$failure=null;
 try{$v=json_decode(...$args);}catch(Throwable $e){$failure=[get_class($e),$e->getMessage(),$e->getCode()];}
 echo json_encode([$id,$before,base64_encode(serialize($v)),json_last_error(),json_last_error_msg(),$events,$failure]),"\n";
}
row("throw-syntax-preserves-different-error",['{',null,512,JSON_THROW_ON_ERROR]);
row("throw-success-preserves-error",['null',null,512,JSON_THROW_ON_ERROR]);
row("normal-success-resets-error",['null']);
row("type-error-preserves-error",[[]]);
row("value-error-resets-error",['0',null,0]);
row("empty-depth-shortcut",['',null,0]);
row("stringable-before-bad-depth",[new DecodeText,null,[]]);
row("stringable-valid",[new DecodeText]);
row("bad-depth-before-bad-flags",['0',null,[],[]]);
row("nul-key-before-malformed-sibling",['{"\u0000x":1,bad}',false]);
row("nul-key-before-invalid-sibling-value",['{"\u0000x":1,"b":}',false]);
row("nul-key-before-unclosed-object",['{"\u0000x":1',false]);
