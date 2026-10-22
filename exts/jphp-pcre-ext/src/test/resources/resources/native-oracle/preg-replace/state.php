<?php
function pr_pack($v){
    if(is_string($v))return ['bytes',bin2hex($v)];
    if(is_object($v))return ['object',get_class($v),pr_pack(get_object_vars($v))];
    if(is_array($v)){$r=[];foreach($v as $k=>$x)$r[]=[is_int($k)?['int',$k]:['bytes',bin2hex($k)],pr_pack($x)];return ['array',$r];}
    return $v;
}
function pr_emit($id,$v){echo $id,':',json_encode(pr_pack($v),JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";}
function pr_trial($id,$fn,$pattern,$replacement,$subject,$limit=-1){
    preg_match('~.~u',"\xff");$count=['sentinel'];$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try{
        if($fn==='replace')$r=preg_replace($pattern,$replacement,$subject,$limit,$count);
        else $r=preg_filter($pattern,$replacement,$subject,$limit,$count);
        $ex=null;
    }catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    pr_emit($id,[$r,$count,$pattern,$replacement,$subject,preg_last_error(),preg_last_error_msg(),$events,$ex]);
}
class PrString {public $text;public function __construct($text){$this->text=$text;}public function __toString(){return $this->text;}}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
$subject='a';$out=preg_replace('~a~','b',$subject,-1,$subject);
pr_emit('count-alias-subject',[$out,$subject]);
$pattern='~a~';$events=[];set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s];return true;});$out=preg_replace($pattern,'b','a',-1,$pattern);restore_error_handler();
pr_emit('count-alias-pattern',[$out,$pattern,$events,preg_last_error()]);
$replacement='b';$out=preg_replace('~a~',$replacement,'a',-1,$replacement);
pr_emit('count-alias-replacement',[$out,$replacement]);
$subject=['k'=>'a'];$out=preg_replace('~a~','b',$subject,-1,$subject);
pr_emit('count-alias-array-subject',[$out,$subject]);
$count=99;$alias=&$count;$out=preg_replace('~a~','b','aa',-1,$count);
pr_emit('count-reference',[$out,$count,$alias]);
$count=99;$events=[];set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s];return true;});$out=preg_replace('~(~','b','aa',-1,$count);restore_error_handler();
pr_emit('count-reset-compile-error',[$out,$count,$events,preg_last_error()]);
$count=99;try{$out=preg_replace('~a~',[],'a',-1,$count);$ex=null;}catch(Throwable $e){$out='not-returned';$ex=[get_class($e),$e->getMessage()];}
pr_emit('count-reset-validation',[$out,$count,$ex,preg_last_error()]);
$x='a';$input=[&$x];$out=preg_replace('~a~','b',$input);$out[0]='c';$afterOut=[$x,$input,$out];$x='d';
pr_emit('replace-reference-copy',[$afterOut,[$x,$input,$out]]);
$x='a';$input=[&$x];$out=preg_filter('~a~','b',$input);$out[0]='c';$x='d';
pr_emit('filter-reference-copy',[$x,$input,$out]);
$input=['k'=>'a'];$out=preg_replace('~a~','b',$input);$out['k']='c';
pr_emit('subject-array-cow',[$input,$out]);
class PrMutator {
    public $mode;
    public function __construct($mode){$this->mode=$mode;}
    public function __toString(){
        global $prInput,$prCalls,$prReplacement;
        $prCalls[]=$this->mode;
        if($this->mode==='append')$prInput['added']='a';
        if($this->mode==='unset')unset($prInput['tail']);
        if($this->mode==='replace')$prInput['tail']='b';
        if($this->mode==='clear')$prInput=[];
        if($this->mode==='replacement')$prReplacement='z';
        if($this->mode==='reenter')$prCalls[]=preg_replace('~a~u','x',"\xff");
        return 'a';
    }
}
$prCalls=[];$prInput=['first'=>new PrMutator('append'),'tail'=>'a'];$out=preg_replace('~a~','x',$prInput);
pr_emit('subject-stringable-append',[$out,$prInput,$prCalls]);
$prCalls=[];$prInput=['first'=>new PrMutator('unset'),'tail'=>'a'];$out=preg_replace('~a~','x',$prInput);
pr_emit('subject-stringable-unset',[$out,$prInput,$prCalls]);
$prCalls=[];$prInput=['first'=>new PrMutator('replace'),'tail'=>'a'];$out=preg_replace('~a~','x',$prInput);
pr_emit('subject-stringable-replace',[$out,$prInput,$prCalls]);
$prCalls=[];$prInput=['first'=>new PrMutator('clear'),'tail'=>'a'];$out=preg_replace('~a~','x',$prInput);
pr_emit('subject-stringable-clear',[$out,$prInput,$prCalls]);
$prCalls=[];$prReplacement='x';$prInput=['first'=>new PrMutator('replacement'),'tail'=>'a'];$out=preg_replace('~a~',$prReplacement,$prInput);
pr_emit('stringable-replacement-alias',[$out,$prReplacement,$prInput,$prCalls]);
$prCalls=[];$prInput=['first'=>new PrMutator('reenter'),'tail'=>'a'];$out=preg_replace('~a~','x',$prInput);
pr_emit('stringable-reentry-errorstate',[$out,$prInput,$prCalls,preg_last_error()]);
$events=[];set_error_handler(function($n,$s)use(&$events){$c=99;$v=preg_replace('~a~','b','aa',1,$c);$events[]=[$n,$s,$v,$c,preg_last_error()];return true;});$count=99;$out=preg_replace('~(~','x','a',-1,$count);restore_error_handler();
pr_emit('warning-reentry',[$out,$count,$events,preg_last_error()]);
set_error_handler(function($n,$s){throw new RuntimeException('handler');});$count=99;$out='not-assigned';try{$out=preg_replace(['~a~','~(~'],['x','y'],'a',-1,$count);$ex=null;}catch(Throwable $e){$ex=[get_class($e),$e->getMessage()];}restore_error_handler();
pr_emit('warning-throw-prefix-count',[$out,$count,$ex,preg_last_error()]);
class PrCount {public int $count=99;}$o=new PrCount();$out=preg_replace('~a~','b','aa',-1,$o->count);
pr_emit('typed-count',[$out,$o->count]);
class PrThrow {public function __toString(){throw new RuntimeException('stringable');}}$count=99;$out='not-assigned';try{$out=preg_replace('~a~','x',['first'=>'a','bad'=>new PrThrow()],-1,$count);$ex=null;}catch(Throwable $e){$ex=[get_class($e),$e->getMessage()];}
pr_emit('stringable-throw-count',[$out,$count,$ex,preg_last_error()]);
