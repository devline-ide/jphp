<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}

class EncDepth implements JsonSerializable {public function jsonSerialize():mixed{$GLOBALS['events'][]='depth-callback';return ['x'=>0];}}
row('scalar-depth0',fn()=>json_encode(7,0,0));
row('scalar-depth-negative',fn()=>json_encode('x',0,-1));
row('empty-array-depth0',fn()=>json_encode([],0,0));
row('empty-object-depth0',fn()=>json_encode(new stdClass,0,0));
row('flat-array-depth1',fn()=>json_encode([0],0,1));
row('nested-partial-depth1',fn()=>json_encode([[0]],JSON_PARTIAL_OUTPUT_ON_ERROR,1));
row('empty-child-depth1',fn()=>json_encode([[]],0,1));
row('container-depth-negative',fn()=>json_encode([0],0,-1));
row('scalar-depth-above-int32',fn()=>json_encode(7,0,2147483648));
row('array-depth-above-int32',fn()=>json_encode([0],0,2147483648));
row('callback-depth0',fn()=>json_encode(new EncDepth,0,0));
row('callback-depth1',fn()=>json_encode(new EncDepth,0,1));
row('weak-depth-loss-diagnostic',fn()=>json_encode([0],0,1.5));
row('strict-depth-string',fn()=>eval('declare(strict_types=1);return json_encode([0],0,"1");'));
