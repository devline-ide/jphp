<?php
function gg_pack($v) {
    if(is_string($v))return ['bytes',bin2hex($v)];
    if(is_resource($v))return ['resource',get_resource_type($v)];
    if(is_object($v))return ['object',get_class($v),gg_pack(get_object_vars($v))];
    if(is_array($v)){$r=[];foreach($v as $k=>$x)$r[]=[is_int($k)?['int',$k]:['bytes',bin2hex($k)],gg_pack($x)];return ['array',$r];}
    return $v;
}
function gg_emit($id,$v){echo $id,':',json_encode(gg_pack($v),JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";}
function gg_trial($id,$pattern,$input,$flags=0){
    preg_match('~.~u',"\xff");$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try{$r=preg_grep($pattern,$input,$flags);$ex=null;}
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    gg_emit($id,[$r,$input,preg_last_error(),preg_last_error_msg(),$events,$ex]);
}
class GgString {public $text;public function __construct($text){$this->text=$text;}public function __toString(){return $this->text;}}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
class GgLimitString {
    public $limit;
    public function __construct($limit){$this->limit=$limit;}
    public function __toString(){
        global $deltaCalls;
        $before=ini_get('pcre.backtrack_limit');
        ini_set('pcre.backtrack_limit',(string)$this->limit);
        $deltaCalls[]=[$before,ini_get('pcre.backtrack_limit')];
        return str_repeat('a',12).'!';
    }
}
$pattern='~(*NO_JIT)(*NO_START_OPT)(?:^ok$|(?:a+)+$)~';
ini_set('pcre.jit','0');ini_set('pcre.backtrack_limit','1000000');ini_set('pcre.recursion_limit','100000');
preg_match('~.~u',"\xff");$deltaCalls=[];$input=['keep'=>'ok','change'=>new GgLimitString(10),'tail'=>'ok'];$out=preg_grep($pattern,$input);
gg_emit('stringable-lowers-match-limit',[$out,$input,$deltaCalls,preg_last_error(),preg_last_error_msg(),ini_get('pcre.backtrack_limit')]);
ini_set('pcre.backtrack_limit','10');preg_match('~.~u',"\xff");$deltaCalls=[];$input=['keep'=>'ok','change'=>new GgLimitString(1000000),'tail'=>'ok'];$out=preg_grep($pattern,$input);
gg_emit('stringable-raises-match-limit',[$out,$input,$deltaCalls,preg_last_error(),preg_last_error_msg(),ini_get('pcre.backtrack_limit')]);
ini_set('pcre.backtrack_limit','1000000');ini_set('pcre.recursion_limit','100000');preg_match('~.~u',"\xff");$events=[];
set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s,ini_get('pcre.recursion_limit')];ini_set('pcre.recursion_limit','1');return true;});
$input=['keep'=>'Ar','change'=>[],'tail'=>'Ar'];$out=preg_grep('~(*NO_JIT)(*NO_START_OPT)(?<n>A(?&n)?r)~',$input);restore_error_handler();
gg_emit('warning-lowers-depth-limit',[$out,$input,$events,preg_last_error(),preg_last_error_msg(),ini_get('pcre.recursion_limit')]);
ini_set('pcre.backtrack_limit','1000000');ini_set('pcre.recursion_limit','100000');
