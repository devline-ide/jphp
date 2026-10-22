<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}

enum EncUnit {case A;}
enum EncBacked:string {case A='v';}
row('nonfinite-default',fn()=>json_encode([INF,-INF,NAN]));
row('nonfinite-partial',fn()=>json_encode([INF,-INF,NAN],JSON_PARTIAL_OUTPUT_ON_ERROR));
row('nonfinite-throw',fn()=>json_encode([INF,-INF,NAN],JSON_THROW_ON_ERROR));
row('resource-default',function(){$r=fopen('php://memory','w+');try{return json_encode($r);}finally{fclose($r);}});
row('resource-array-partial',function(){$r=fopen('php://memory','w+');try{return json_encode([$r],JSON_PARTIAL_OUTPUT_ON_ERROR);}finally{fclose($r);}});
row('backed-and-unit-partial',fn()=>json_encode([EncBacked::A,EncUnit::A],JSON_PARTIAL_OUTPUT_ON_ERROR));
row('unit-enum-throw',fn()=>json_encode(EncUnit::A,JSON_THROW_ON_ERROR));
row('numeric-string-check',fn()=>json_encode(['1e2','+12',' 5 ','01','-0.0','1e309','9223372036854775808'],JSON_NUMERIC_CHECK));
row('fraction-default',fn()=>json_encode([1.0,-0.0]));
row('fraction-preserved',fn()=>json_encode([1.0,-0.0],JSON_PRESERVE_ZERO_FRACTION));
row('unicode-lines-slash-default',fn()=>json_encode("<>&'\"/\u{00e9}\u{2028}\u{2029}"));
row('hex-unicode-slash-lines',fn()=>json_encode("<>&'\"/\u{00e9}\u{2028}\u{2029}",JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT|JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_LINE_TERMINATORS));
row('pretty-force-object',fn()=>json_encode([[0],['x'=>1],[]],JSON_PRETTY_PRINT|JSON_FORCE_OBJECT));
