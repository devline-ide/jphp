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
$cbCalls=[];$subject='a';$out=preg_replace_callback('~a~',cb_make('fixed'),$subject,-1,$subject);
cb_emit('count-alias-subject',[$out,$subject,$cbCalls]);
$cbCalls=[];$pattern='~a~';$out=preg_replace_callback($pattern,cb_make('fixed'),'a',-1,$pattern);
cb_emit('count-alias-pattern',[$out,$pattern,$cbCalls]);
$cbCalls=[];$map=['~a~'=>cb_make('fixed')];$out=preg_replace_callback_array($map,'a',-1,$map);
cb_emit('count-alias-map',[$out,$map,$cbCalls]);
$cbCalls=[];$x='a';$input=[&$x];$out=preg_replace_callback('~a~',cb_make('fixed'),$input);$out[0]='out';$x='changed';
cb_emit('subject-reference-copy',[$out,$input,$x,$cbCalls]);
$cbCalls=[];$input=[12,new CbString('a')];$out=preg_replace_callback_array([],$input);$same=$out[1]===$input[1];$out[0]='out';
cb_emit('empty-map-original-types-cow',[$out,$input,$same]);
class CbSourceMutator {
    public function __toString(){global $cbInput;unset($cbInput['tail']);$cbInput['added']='a';return 'a';}
}
$cbCalls=[];$cbInput=['first'=>new CbSourceMutator(),'tail'=>'a'];$out=preg_replace_callback('~a~',cb_make('fixed'),$cbInput);
cb_emit('stringable-source-snapshot',[$out,$cbInput,$cbCalls]);
$cbInput=['first'=>'a','tail'=>'a'];$cbCalls=[];$callback=function($m){global $cbInput,$cbCalls;$cbCalls[]=$m;unset($cbInput['tail']);$cbInput['added']='a';return 'X';};$out=preg_replace_callback('~a~',$callback,$cbInput);
cb_emit('callback-source-snapshot',[$out,$cbInput,$cbCalls]);
cb_trial('nested-preg-errorstate','~a~',cb_make('nested-error'),'aa');
$count=777;$calls=0;$out='not-assigned';$callback=function($m)use(&$calls){$calls++;if($calls===2)throw new RuntimeException('second');return 'X';};try{$out=preg_replace_callback('~a~',$callback,'aa',-1,$count);$ex=null;}catch(Throwable $e){$ex=[get_class($e),$e->getMessage()];}
cb_emit('throw-second-count',[$out,$count,$calls,$ex,preg_last_error()]);
$count=777;$calls=0;$out='not-assigned';$callback=function($m)use(&$calls){$calls++;if($calls===2)throw new RuntimeException('second');return 'X';};try{$out=preg_replace_callback_array(['~a~'=>$callback],'aa',-1,$count);$ex=null;}catch(Throwable $e){$ex=[get_class($e),$e->getMessage()];}
cb_emit('map-throw-second-count',[$out,$count,$calls,$ex,preg_last_error()]);
$events=[];set_error_handler(function($n,$s)use(&$events){$c=99;$v=preg_replace_callback('~a~',function($m){return 'Y';},'a',-1,$c);$events[]=[$n,$s,$v,$c,preg_last_error()];return true;});$count=777;$out=preg_replace_callback('~(~',function($m){return 'X';},'a',-1,$count);restore_error_handler();
cb_emit('warning-reentry',[$out,$count,$events,preg_last_error()]);
set_error_handler(function($n,$s){throw new RuntimeException('handler');});$count=777;$out='not-assigned';try{$out=preg_replace_callback('~a~',function($m){return [];},'a',-1,$count);$ex=null;}catch(Throwable $e){$ex=[get_class($e),$e->getMessage()];}restore_error_handler();
cb_emit('return-array-warning-throw',[$out,$count,$ex,preg_last_error()]);
class CbReturnLimit {public function __toString(){ini_set('pcre.backtrack_limit','10');return 'X';}}
ini_set('pcre.jit','0');ini_set('pcre.backtrack_limit','1000000');$count=777;$calls=0;$callback=function($m)use(&$calls){$calls++;return new CbReturnLimit();};$out=preg_replace_callback('~(*NO_JIT)(*NO_START_OPT)(?:k|(?:a+)+b)~',$callback,'k '.str_repeat('a',12).'!',-1,$count);
cb_emit('return-stringable-changes-limit',[$out,$count,$calls,preg_last_error(),preg_last_error_msg(),ini_get('pcre.backtrack_limit')]);
ini_set('pcre.backtrack_limit','1000000');$cbCalls=[];$first=function($m){preg_match('~.~u',"\xff");return 'X';};$second=function($m){global $cbCalls;$cbCalls[]=preg_last_error();return 'Y';};$count=777;$out=preg_replace_callback_array(['~a~'=>$first,'~X~'=>$second],'a',-1,$count);
cb_emit('next-pattern-resets-errorstate',[$out,$count,$cbCalls,preg_last_error()]);
$cbSubject='aa';$cbCalls=[];$callback=function($m){global $cbSubject,$cbCalls;$cbCalls[]=$m;$cbSubject='zz';return 'X';};$count=777;$out=preg_replace_callback('~a~',$callback,$cbSubject,-1,$count);
cb_emit('callback-fixed-subject-bytes',[$out,$cbSubject,$cbCalls,$count,preg_last_error()]);
