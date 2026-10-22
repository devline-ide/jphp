<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}

row('utf8-consume-c0af',fn()=>json_encode("\xC0\xAF",JSON_INVALID_UTF8_SUBSTITUTE));
row('utf8-consume-f5808080',fn()=>json_encode("\xF5\x80\x80\x80",JSON_INVALID_UTF8_SUBSTITUTE));
row('utf8-consume-e282',fn()=>json_encode("\xE2\x82",JSON_INVALID_UTF8_SUBSTITUTE));
row('utf8-consume-e28241',fn()=>json_encode("\xE2\x82\x41",JSON_INVALID_UTF8_SUBSTITUTE));
row('utf8-consume-e028a1',fn()=>json_encode("\xE0\x28\xA1",JSON_INVALID_UTF8_SUBSTITUTE));
row('utf8-consume-80bf',fn()=>json_encode("\x80\xBF",JSON_INVALID_UTF8_SUBSTITUTE));
row('utf8-consume-f888808080',fn()=>json_encode("\xF8\x88\x80\x80\x80",JSON_INVALID_UTF8_SUBSTITUTE));
