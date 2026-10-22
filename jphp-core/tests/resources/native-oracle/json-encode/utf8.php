<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}

row('invalid-key-default',fn()=>json_encode(["\xC3\x28"=>1]));
row('invalid-key-ignore',fn()=>json_encode(["\xC3\x28"=>1],JSON_INVALID_UTF8_IGNORE));
row('invalid-key-substitute',fn()=>json_encode(["\xC3\x28"=>1],JSON_INVALID_UTF8_SUBSTITUTE));
row('invalid-key-both',fn()=>json_encode(["\xC3\x28"=>1],JSON_INVALID_UTF8_IGNORE|JSON_INVALID_UTF8_SUBSTITUTE));
row('invalid-key-partial',fn()=>json_encode(["\xC3\x28"=>1],JSON_PARTIAL_OUTPUT_ON_ERROR));
row('invalid-key-throw',fn()=>json_encode(["\xC3\x28"=>1],JSON_THROW_ON_ERROR));
row('invalid-value-both-and-throw',fn()=>json_encode("\xC3\x28",JSON_INVALID_UTF8_IGNORE|JSON_INVALID_UTF8_SUBSTITUTE|JSON_THROW_ON_ERROR));
row('invalid-overlong-substitute',fn()=>json_encode("a\xF0\x80\x80\x80z",JSON_INVALID_UTF8_SUBSTITUTE));
row('invalid-surrogate-substitute',fn()=>json_encode("\xED\xA0\x80",JSON_INVALID_UTF8_SUBSTITUTE));
row('nested-value-partial-beats-throw',fn()=>json_encode(['ok'=>1,'bad'=>"\xC3\x28"],JSON_PARTIAL_OUTPUT_ON_ERROR|JSON_THROW_ON_ERROR));
