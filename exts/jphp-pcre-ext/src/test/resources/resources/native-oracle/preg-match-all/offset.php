<?php
function offset_case($id,$fn,$pattern,$flags,$offset) {
    preg_match('~.~u',"\xff");
    $m=['sentinel'];$events=[];
    set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s];return true;});
    try{$r=$fn($pattern,'a',$m,$flags,$offset);$ex=null;}
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    echo $id,':',json_encode([$r,$m,preg_last_error(),preg_last_error_msg(),$events,$ex]),"\n";
}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
foreach(['preg_match','preg_match_all']as $fn){
    offset_case($fn.'-min',$fn,'~a~',0,-PHP_INT_MAX-1);
    offset_case($fn.'-min-invalid-pattern',$fn,'~(~',0,-PHP_INT_MAX-1);
    offset_case($fn.'-min-invalid-flags',$fn,'~a~',3,-PHP_INT_MAX-1);
    offset_case($fn.'-min-plus-one',$fn,'~a~',0,-PHP_INT_MAX);
}
