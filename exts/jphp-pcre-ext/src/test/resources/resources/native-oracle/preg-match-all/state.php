<?php
function pma_pack($v) {
    if (is_string($v)) return ['bytes',bin2hex($v)];
    if (is_array($v)) { $r=[]; foreach($v as $k=>$x)$r[]=[is_int($k)?['int',$k]:['bytes',bin2hex($k)],pma_pack($x)]; return ['array',$r]; }
    return $v;
}
function pma_emit($id,$v){echo $id,':',json_encode(pma_pack($v),JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
pma_emit('arity-2',[preg_match_all('~a~','aaa'),preg_last_error()]);
$m=23;$r=preg_match_all('~(a)~','aa',$m);pma_emit('arity-3-scalar-matches',[$r,$m,preg_last_error()]);
$m=null;$r=preg_match_all('~(a)~','aa',$m,PREG_SET_ORDER);pma_emit('arity-4-null-matches',[$r,$m,preg_last_error()]);
$m=null;$r=preg_match_all('~(a)~','aa',$m,PREG_SET_ORDER,1);pma_emit('arity-5-offset',[$r,$m,preg_last_error()]);
set_error_handler(function($n,$s){throw new RuntimeException('handler');});$m=['seed'];try{preg_match_all('~(~','a',$m);}catch(Throwable $e){pma_emit('warning-throw',[get_class($e),$e->getMessage(),$m,preg_last_error()]);}restore_error_handler();
$events=[];set_error_handler(function($n,$s)use(&$events){$before=preg_last_error();$ok=preg_match('~a~','a');$events[]=[$before,$ok,preg_last_error()];return true;});$m=['seed'];$r=preg_match_all('~(~','a',$m);restore_error_handler();pma_emit('warning-reentry-old-match',[$r,$m,$events,preg_last_error()]);
class PmaTypedMatches {public string $text='seed';}$o=new PmaTypedMatches();try{preg_match_all('~a~','aa',$o->text);}catch(Throwable $e){pma_emit('typed-matches',[get_class($e),$e->getMessage(),$o->text,preg_last_error()]);}
ini_set('pcre.jit','0');ini_set('pcre.backtrack_limit','10');$m=['seed'];$r=preg_match_all('~^(a+)+$~',str_repeat('a',30).'!',$m);pma_emit('backtrack',[$r,$m,preg_last_error(),preg_last_error_msg()]);
ini_set('pcre.backtrack_limit','1000000');ini_set('pcre.recursion_limit','1');$r=preg_match_all('~(?<n>a(?&n)?b)~','aaaabbbb',$m);pma_emit('depth',[$r,$m,preg_last_error(),preg_last_error_msg()]);ini_set('pcre.recursion_limit','100000');
$r=preg_match_all('~b~','aa',$m);pma_emit('success-resets-error',[$r,$m,preg_last_error(),preg_last_error_msg()]);
$m=['seed'];$r=preg_match_all('~(?<MARK>a)(*MARK:chosen)|b~','ab',$m,PREG_PATTERN_ORDER|PREG_OFFSET_CAPTURE);pma_emit('mark-name-collision',[$r,$m,preg_last_error()]);
$m=['seed'];$r=preg_match_all('~(*NO_JIT)(*NO_START_OPT)(*LIMIT_MATCH=30)(?:x|(?:a+)+b)~','x '.str_repeat('a',30).'!',$m,PREG_PATTERN_ORDER);pma_emit('error-after-partial',[$r,$m,preg_last_error(),preg_last_error_msg()]);
