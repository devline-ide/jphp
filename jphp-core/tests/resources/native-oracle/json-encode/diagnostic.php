<?php
function row($id,$call){
    json_decode('{');$events=[];$result=null;$failure=null;
    set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}
row('invalid-utf8',fn()=>json_encode("\xC3\x28"));
row('invalid-ignore',fn()=>json_encode("\xC3\x28",JSON_INVALID_UTF8_IGNORE));
row('invalid-substitute',fn()=>json_encode("\xC3\x28",JSON_INVALID_UTF8_SUBSTITUTE));
row('invalid-both-flags',fn()=>json_encode("\xC3\x28",JSON_INVALID_UTF8_IGNORE|JSON_INVALID_UTF8_SUBSTITUTE));
row('invalid-throw-preserves-syntax',fn()=>json_encode("\xC3\x28",JSON_THROW_ON_ERROR));
row('invalid-partial-beats-throw',fn()=>json_encode("\xC3\x28",JSON_THROW_ON_ERROR|JSON_PARTIAL_OUTPUT_ON_ERROR));
row('embedded-nul',fn()=>json_encode("a\0b"));
row('hex-slash-unicode-flags',fn()=>json_encode("<&'\"/\u{00e9}",JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT|JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE));
row('positive-depth-enforced',fn()=>json_encode([[0]],0,1));
row('zero-depth',fn()=>json_encode([0],0,0));
row('weak-flags-loss-diagnostic',fn()=>json_encode('x',1.5));
row('strict-flags-string',fn()=>eval('declare(strict_types=1);return json_encode("x","0");'));
