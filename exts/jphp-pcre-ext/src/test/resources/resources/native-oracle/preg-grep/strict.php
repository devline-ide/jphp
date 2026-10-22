<?php
declare(strict_types=1);
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
gg_trial('pattern-int',123,['123']);
gg_trial('pattern-null',null,['a']);
gg_trial('pattern-stringable',new GgString('~a~'),['a','b']);
gg_trial('pattern-array',[],['a']);
gg_trial('input-null','~a~',null);
gg_trial('input-int','~a~',1);
gg_trial('input-string','~a~','a');
gg_trial('input-object','~a~',new GgString('a'));
gg_trial('flags-string','~a~',['a','b'],'1');
gg_trial('flags-float','~a~',['a','b'],1.5);
gg_trial('flags-null','~a~',['a','b'],null);
gg_trial('flags-array','~a~',['a','b'],[]);
gg_trial('argument-order',[],null,[]);
gg_trial('invalid-pattern-input-order','~(~',null,[]);
gg_trial('empty-input-invalid-flags-order','~(~',[],[]);
