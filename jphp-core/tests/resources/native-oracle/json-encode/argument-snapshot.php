<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}


row('flags-warning-mutates-array-send',function(){
    $a=['old'=>1];$alias=&$a;
    set_error_handler(function($n,$s)use(&$alias){$GLOBALS['events'][]=[$n,$s];unset($alias['old']);$alias['new']=2;return true;});
    try{return json_encode($alias,1.5);}finally{restore_error_handler();}
});
row('flags-warning-throws-before-encode',function(){
    $a=['old'=>1];
    set_error_handler(function($n,$s)use(&$a){$GLOBALS['events'][]=[$n,$s];$a=['new'=>2];throw new Exception('encode-flags-handler');});
    try{return json_encode($a,1.5);}finally{restore_error_handler();}
});
