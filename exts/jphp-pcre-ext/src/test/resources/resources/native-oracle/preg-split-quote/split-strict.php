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
class PsString {public function __toString(){return 'a,b';}}
ps_trial('pattern-int',123,'123123');
ps_trial('pattern-null',null,'abc');
ps_trial('pattern-array',[],'abc');
ps_trial('subject-int','~2~',1212);
ps_trial('subject-bool','~1~',true);
ps_trial('subject-null','~~',null);
ps_trial('subject-stringable','~,~',new PsString());
ps_trial('subject-array','~,~',[]);
ps_trial('limit-string','~,~','a,b,c','2');
ps_trial('limit-float','~,~','a,b,c',2.5);
ps_trial('limit-null','~,~','a,b,c',null);
ps_trial('limit-array','~,~','a,b,c',[]);
ps_trial('flags-string','~,~',',a,',-1,'1');
ps_trial('flags-float','~,~',',a,',-1,1.5);
ps_trial('flags-null','~,~',',a,',-1,null);
ps_trial('flags-array','~,~','a,b',-1,[]);
ps_trial('argument-order','~(~',[],[],[]);
ps_trial('argument-order-pattern',[],[],[],[]);
