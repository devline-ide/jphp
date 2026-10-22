<?php
function cb_pack($v){
    if(is_string($v))return ['bytes',bin2hex($v)];
    if(is_object($v))return ['object',get_class($v),cb_pack(get_object_vars($v))];
    if(is_array($v)){$r=[];foreach($v as $k=>$x)$r[]=[is_int($k)?['int',$k]:['bytes',bin2hex($k)],cb_pack($x)];return ['array',$r];}
    return $v;
}
function cb_emit($id,$v){echo $id,':',json_encode(cb_pack($v),JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";}
class CbString {public $text;public function __construct($text){$this->text=$text;}public function __toString(){return $this->text;}}
function cb_make($kind){return function($m)use($kind){
    global $cbCalls;$cbCalls[]=[$kind,$m];
    if($kind==='bracket')return '['.$m[0].']';
    if($kind==='reverse')return $m[2].'-'.$m[1];
    if($kind==='literal')return '$1-\1-${1}';
    if($kind==='int')return 23;
    if($kind==='float')return 1.5;
    if($kind==='bool')return false;
    if($kind==='null')return null;
    if($kind==='array')return ['x'];
    if($kind==='stringable')return new CbString('Z');
    if($kind==='object')return new stdClass();
    if($kind==='binary')return "\xff\x00";
    if($kind==='throw')throw new RuntimeException('callback');
    if($kind==='nested-error')preg_match('~.~u',"\xff");
    if($kind==='mutate-matches')$m[0]='local';
    return 'X';
};}
function cb_named($m){global $cbCalls;$cbCalls[]=['named',$m];return strtoupper($m[0]);}
function cb_required_ref(&$m){global $cbCalls;$cbCalls[]=['byref',$m];return 'R';}
class CbCallable {
    public static function run($m){global $cbCalls;$cbCalls[]=['static',$m];return 'S';}
    public function __invoke($m){global $cbCalls;$cbCalls[]=['invoke',$m];return 'I';}
}
function cb_trial($id,$pattern,$callback,$subject,$limit=-1,$flags=0){
    global $cbCalls;$cbCalls=[];preg_match('~.~u',"\xff");$count=777;$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try{$r=preg_replace_callback($pattern,$callback,$subject,$limit,$count,$flags);$ex=null;}
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    cb_emit($id,[$r,$count,$cbCalls,preg_last_error(),preg_last_error_msg(),$events,$ex]);
}
function ca_trial($id,$map,$subject,$limit=-1,$flags=0){
    global $cbCalls;$cbCalls=[];preg_match('~.~u',"\xff");$count=777;$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try{$r=preg_replace_callback_array($map,$subject,$limit,$count,$flags);$ex=null;}
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    cb_emit($id,[$r,$count,$cbCalls,preg_last_error(),preg_last_error_msg(),$events,$ex]);
}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
cb_trial('pattern-int',123,cb_make('fixed'),'123');
cb_trial('pattern-null',null,cb_make('fixed'),'a');
cb_trial('pattern-stringable',new CbString('~a~'),cb_make('fixed'),'a');
cb_trial('subject-int','~2~',cb_make('fixed'),1212);
cb_trial('subject-null','~~',cb_make('fixed'),null);
cb_trial('subject-stringable','~a~',cb_make('fixed'),new CbString('a'));
cb_trial('limit-string','~a~',cb_make('fixed'),'aa','1');
cb_trial('flags-string','~a~',cb_make('fixed'),'a',-1,'256');
cb_trial('invalid-callback-null','~a~',null,'a');
cb_trial('invalid-callback-order','~(~','not_a_callback',null,[],[]);
ca_trial('invalid-map-type','not_an_array','a');
ca_trial('map-invalid-subject',['~a~'=>cb_make('fixed')],new stdClass());
