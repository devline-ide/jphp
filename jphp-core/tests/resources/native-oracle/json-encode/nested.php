<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}


class EncNestedSelf implements JsonSerializable {
    public function jsonSerialize():mixed{$GLOBALS['events'][]='nested-self-enter';$r=json_encode($this);$GLOBALS['events'][]=['nested-self-result',base64_encode(serialize($r)),json_last_error()];return $r;}
}
class EncNestedOuter implements JsonSerializable {
    public function jsonSerialize():mixed{$GLOBALS['events'][]='nested-outer-enter';$r=json_encode($GLOBALS['outerArray']);$GLOBALS['events'][]=['nested-outer-result',base64_encode(serialize($r)),json_last_error()];return $r;}
}
row('nested-json-encode-this',fn()=>json_encode(new EncNestedSelf));
row('nested-json-encode-outer-array',function(){$o=new EncNestedOuter;$GLOBALS['outerArray']=[$o];try{return json_encode($GLOBALS['outerArray']);}finally{unset($GLOBALS['outerArray']);}});
row('numeric-check-overflow-1e999',fn()=>json_encode(['1e999','-1e999','+1e999',' 1e999 '],JSON_NUMERIC_CHECK));
