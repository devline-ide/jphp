<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}


class EncPublicationThrow implements JsonSerializable {public function jsonSerialize():mixed{$GLOBALS['events'][]='publication-throw';throw new Exception('encode-publication-callback');}}
row('callback-throw-no-throwflag',fn()=>json_encode(new EncPublicationThrow));
row('callback-throw-partial-and-throwflag',fn()=>json_encode(new EncPublicationThrow,JSON_PARTIAL_OUTPUT_ON_ERROR|JSON_THROW_ON_ERROR));
row('nonfinite-then-callback-throw-no-throwflag',fn()=>json_encode([INF,new EncPublicationThrow]));
row('nonfinite-then-callback-throw-throwflag',fn()=>json_encode([INF,new EncPublicationThrow],JSON_THROW_ON_ERROR));
