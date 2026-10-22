<?php
declare(strict_types=1);
function psq_pack($v) {
    if (is_string($v)) return ['bytes',bin2hex($v)];
    if (is_array($v)) { $r=[]; foreach($v as $k=>$x)$r[]=[is_int($k)?['int',$k]:['bytes',bin2hex($k)],psq_pack($x)]; return ['array',$r]; }
    return $v;
}
function psq_emit($id,$v){echo $id,':',json_encode(psq_pack($v),JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";}
function ps_trial($id,$p,$s,$limit=-1,$flags=0) {
    preg_match('~.~u',"\xff");$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try{$r=preg_split($p,$s,$limit,$flags);$ex=null;}
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    psq_emit($id,[$r,preg_last_error(),preg_last_error_msg(),$events,$ex]);
}
function pq_trial($id,$s,$delimiter=null) {
    preg_match('~.~u',"\xff");$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try{$r=preg_quote($s,$delimiter);$ex=null;}
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    psq_emit($id,[$r,preg_last_error(),preg_last_error_msg(),$events,$ex]);
}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
class PqString {public function __toString(){return 'a.b';}}
pq_trial('string-int',123);
pq_trial('string-float',12.5);
pq_trial('string-bool',true);
pq_trial('string-null',null);
pq_trial('string-stringable',new PqString());
pq_trial('string-array',[]);
pq_trial('delimiter-int','123123',12);
pq_trial('delimiter-float','12.5',12.5);
pq_trial('delimiter-bool','a1a',true);
pq_trial('delimiter-null-allowed','a.b',null);
pq_trial('delimiter-stringable','a.b',new PqString());
pq_trial('delimiter-array','a.b',[]);
pq_trial('argument-order',[],[]);
