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
gg_trial('literal','~a~',['a','b','aa']);
gg_trial('invert','~a~',['a','b','aa'],PREG_GREP_INVERT);
gg_trial('unknown-flag2','~a~',['a','b','aa'],2);
gg_trial('unknown-flag256','~a~',['a','b','aa'],256);
gg_trial('invert-plus-unknown','~a~',['a','b','aa'],PREG_GREP_INVERT|2);
gg_trial('negative-flags','~a~',['a','b','aa'],-1);
gg_trial('long-max-flags','~a~',['a','b','aa'],PHP_INT_MAX);
gg_trial('long-min-flags','~a~',['a','b','aa'],-PHP_INT_MAX-1);
gg_trial('mixed-keys','~a~',[9=>'a',-2=>'b','word'=>'aa','09'=>'a','7'=>'a',"\x00\xff"=>'a']);
gg_trial('key-not-matched','~a~',['a'=>'b','b'=>'a']);
gg_trial('numeric-key-gaps','~a~',[4=>'a',11=>'a',21=>'b',30=>'a']);
gg_trial('no-match','~z~',['a','b']);
gg_trial('empty-array','~a~',[]);
gg_trial('empty-array-invert','~a~',[],PREG_GREP_INVERT);
gg_trial('empty-pattern','~~',['','a',"\x00"]);
gg_trial('empty-pattern-invert','~~',['','a',"\x00"],PREG_GREP_INVERT);
gg_trial('scalar-original-types','~^(?:1|12|12.5|)$~',['text'=>'1','int'=>12,'float'=>12.5,'true'=>true,'false'=>false,'null'=>null]);
gg_trial('array-warning-preserves-types','~Array~',['empty'=>[],'nested'=>['a'],'text'=>'Array']);
gg_trial('array-warning-invert','~Array~',['empty'=>[],'nested'=>['a'],'text'=>'z'],PREG_GREP_INVERT);
gg_trial('stringable-preserves-object','~a~',['first'=>new GgString('a'),'last'=>new GgString('b')]);
gg_trial('object-conversion-error','~a~',['first'=>'a','object'=>new stdClass(),'tail'=>'a']);
$stream=fopen('php://memory','r+');
gg_trial('resource-original-type','~Resource id~',['stream'=>$stream,'tail'=>'a']);
fclose($stream);
gg_trial('utf','~\p{L}~u',['Ж','é','𐍈','1']);
gg_trial('binary-subject','~\x00~',["a\x00b","\xff",'a']);
gg_trial('binary-pattern',"~\xff~",["\xff","a\xff",'a']);
gg_trial('nul-pattern',"~a\x00b~",["a\x00b",'ab']);
gg_trial('utf-error-first','~a~u',['bad'=>"\xff",'tail'=>'a']);
gg_trial('utf-error-partial','~a~u',['keep'=>'a','bad'=>"\xff",'tail'=>'a']);
gg_trial('utf-error-partial-invert','~a~u',['keep'=>'b','bad'=>"\xff",'tail'=>'b'],PREG_GREP_INVERT);
gg_trial('execution-backtrack-partial','~(*NO_JIT)(*NO_START_OPT)(*LIMIT_MATCH=30)(?:^ok$|(?:a+)+b)~',['keep'=>'ok','bad'=>str_repeat('a',30).'!','tail'=>'ok']);
gg_trial('execution-backtrack-first','~(*NO_JIT)(*NO_START_OPT)(*LIMIT_MATCH=30)(?:^ok$|(?:a+)+b)~',['bad'=>str_repeat('a',30).'!','tail'=>'ok']);
gg_trial('invalid-pattern','~(~',['a']);
gg_trial('invalid-pattern-empty','~(~',[]);
gg_trial('invalid-delimiter-empty','abc',[]);
gg_trial('invalid-modifier','~a~q',['a']);
gg_trial('invalid-pattern-utf',"~\xff~u",['a']);
gg_trial('duplicate-names','~(?<x>a)|(?<x>b)~J',['a','b','c']);
gg_trial('keep','~a\K~',['a','aa','b']);
gg_trial('crlf','~(*CRLF)^b$~m',["a\r\nb",'b','c']);
gg_emit('arity-2',[preg_grep('~a~',['a','b']),preg_last_error()]);
$f=new ReflectionFunction('preg_grep');$params=[];foreach($f->getParameters()as $p)$params[]=[$p->getName(),(string)$p->getType(),$p->isPassedByReference(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];
gg_emit('reflection',[(string)$f->getReturnType(),$f->getNumberOfRequiredParameters(),$f->getNumberOfParameters(),$params]);
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
