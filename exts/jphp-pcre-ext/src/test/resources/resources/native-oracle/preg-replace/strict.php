<?php
declare(strict_types=1);
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
pr_trial('pattern-int','replace',123,'x','123');
pr_trial('pattern-null','replace',null,'x','a');
pr_trial('pattern-stringable','replace',new PrString('~a~'),'x','a');
pr_trial('pattern-nonstring-object','replace',new stdClass(),'x','a');
pr_trial('replacement-int','replace','~a~',12,'a');
pr_trial('replacement-null','replace','~a~',null,'a');
pr_trial('replacement-stringable','replace','~a~',new PrString('x'),'a');
pr_trial('replacement-nonstring-object','replace','~a~',new stdClass(),'a');
pr_trial('subject-int','replace','~2~','x',1212);
pr_trial('subject-bool','replace','~1~','x',true);
pr_trial('subject-null','replace','~~','x',null);
pr_trial('subject-stringable','replace','~a~','x',new PrString('a'));
pr_trial('subject-nonstring-object','replace','~a~','x',new stdClass());
pr_trial('limit-string','replace','~a~','x','aaa','1');
pr_trial('limit-float','replace','~a~','x','aaa',1.5);
pr_trial('limit-null','replace','~a~','x','aaa',null);
pr_trial('limit-array','replace','~a~','x','aaa',[]);
pr_trial('argument-order','replace',new stdClass(),new stdClass(),new stdClass(),[]);
pr_trial('replacement-array-order','replace','~(~',[],null);
pr_trial('filter-weak-subject-int','filter','~2~','x',1212);
pr_trial('filter-weak-subject-null','filter','~~','x',null);
pr_trial('filter-limit-array','filter','~a~','x','a',[]);
