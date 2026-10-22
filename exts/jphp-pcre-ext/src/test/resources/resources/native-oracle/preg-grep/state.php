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
$x='a';$input=[&$x];$out=preg_grep('~a~',$input);$before=[$x,$input,$out];$out[0]='b';$afterOut=[$x,$input,$out];$x='c';
gg_emit('reference-output-write',[$before,$afterOut,[$x,$input,$out]]);
$x='a';$input=['key'=>&$x];$out=preg_grep('~a~',$input);$input['key']='b';$afterInput=[$x,$input,$out];unset($input['key']);$x='c';
gg_emit('reference-input-write-unset',[$afterInput,[$x,$input,$out]]);
$x='b';$input=[&$x];$out=preg_grep('~a~',$input);$out[0]='a';
gg_emit('excluded-reference',[$x,$input,$out]);
$input=['key'=>'a'];$out=preg_grep('~a~',$input);$out['key']='b';
gg_emit('ordinary-value-cow',[$input,$out]);
set_error_handler(function($n,$s){return true;});$input=['nested'=>['a']];$out=preg_grep('~Array~',$input);restore_error_handler();$out['nested'][0]='b';
gg_emit('nested-array-cow',[$input,$out]);
$o=new GgString('a');$input=['object'=>$o];$out=preg_grep('~a~',$input);$same=$out['object']===$o;$out['object']->text='b';
gg_emit('object-identity',[$same,$o,$input,$out]);
class GgMutator {
    public $mode;
    public function __construct($mode){$this->mode=$mode;}
    public function __toString(){
        global $ggInput,$ggCalls;
        $ggCalls[]=$this->mode;
        if($this->mode==='append')$ggInput['added']='a';
        if($this->mode==='unset')unset($ggInput['later']);
        if($this->mode==='replace')$ggInput['later']='b';
        if($this->mode==='clear')$ggInput=[];
        if($this->mode==='reenter')$ggCalls[]=preg_grep('~.~u',["\xff"]);
        return 'a';
    }
}
$ggCalls=[];$ggInput=['first'=>new GgMutator('append'),'later'=>'a'];$out=preg_grep('~a~',$ggInput);
gg_emit('stringable-append-snapshot',[$ggCalls,$ggInput,$out]);
$ggCalls=[];$ggInput=['first'=>new GgMutator('unset'),'later'=>'a'];$out=preg_grep('~a~',$ggInput);
gg_emit('stringable-unset-snapshot',[$ggCalls,$ggInput,$out]);
$ggCalls=[];$ggInput=['first'=>new GgMutator('replace'),'later'=>'a'];$out=preg_grep('~a~',$ggInput);
gg_emit('stringable-replace-snapshot',[$ggCalls,$ggInput,$out]);
$ggCalls=[];$ggInput=['first'=>new GgMutator('clear'),'later'=>'a'];$out=preg_grep('~a~',$ggInput);
gg_emit('stringable-clear-snapshot',[$ggCalls,$ggInput,$out]);
$ggCalls=[];$ggInput=['first'=>new GgMutator('reenter'),'later'=>'a'];$out=preg_grep('~a~',$ggInput);
gg_emit('stringable-reentry-errorstate',[$ggCalls,$ggInput,$out,preg_last_error(),preg_last_error_msg()]);
$events=[];set_error_handler(function($n,$s)use(&$events){$events[]=[preg_last_error(),preg_grep('~a~',['a','b']),preg_last_error()];return true;});$out=preg_grep('~Array~',[[],[]]);restore_error_handler();
gg_emit('warning-reentry',[$out,$events,preg_last_error()]);
set_error_handler(function($n,$s){throw new RuntimeException('handler');});$out='not-assigned';try{$out=preg_grep('~Array~',['first'=>'Array','array'=>[]]);$ex=null;}catch(Throwable $e){$ex=[get_class($e),$e->getMessage()];}restore_error_handler();
gg_emit('warning-throw',[$out,$ex,preg_last_error()]);
class GgThrow {public function __toString(){throw new RuntimeException('stringable');}}
$out='not-assigned';try{$out=preg_grep('~a~',['first'=>'a','object'=>new GgThrow(),'tail'=>'a']);$ex=null;}catch(Throwable $e){$ex=[get_class($e),$e->getMessage()];}
gg_emit('stringable-throw',[$out,$ex,preg_last_error()]);
