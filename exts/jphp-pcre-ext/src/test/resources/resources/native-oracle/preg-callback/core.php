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
cb_trial('literal','~a~',cb_make('bracket'),'aba');
cb_trial('no-match','~z~',cb_make('fixed'),'aba');
cb_trial('capture-backrefs','~(a)(b)~',cb_make('reverse'),'abxab');
cb_trial('return-literal-not-backref','~(a)~',cb_make('literal'),'a');
cb_trial('named-captures','~(?<word>a)(?<tail>b)?~',cb_make('fixed'),'a ab');
cb_trial('offset-captures','~(?<word>a)(b)?~',cb_make('fixed'),'za zab',-1,PREG_OFFSET_CAPTURE);
cb_trial('null-captures','~(?<word>a)(b)?(c)?~',cb_make('fixed'),'a ab',-1,PREG_UNMATCHED_AS_NULL);
cb_trial('offset-null-captures','~(?<word>a)(b)?(c)?~',cb_make('fixed'),'za zab',-1,PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
cb_trial('duplicate-names','~(?<x>a)|(?<x>b)~J',cb_make('fixed'),'aba',-1,PREG_UNMATCHED_AS_NULL);
cb_trial('mark-captures','~(?<n>a)(*MARK:tag)|b~',cb_make('fixed'),'ab',-1,PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
cb_trial('mark-name-collision','~(?<MARK>a)(*MARK:tag)|b~',cb_make('fixed'),'ab',-1,PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
cb_trial('mutate-matches-copy','~(?<n>a)~',cb_make('mutate-matches'),'aa');
cb_trial('subject-array-keys','~a~',cb_make('fixed'),[9=>'a',-2=>'b','word'=>'aa',"\x00\xff"=>'a']);
cb_trial('subject-array-original-types','~1~',cb_make('fixed'),[12,true,false,null]);
cb_trial('callback-required-reference','~a~','cb_required_ref','aa');
cb_trial('pattern-array-pipeline',['~a~','~X~'],cb_make('bracket'),'a');
cb_trial('pattern-array-subject-order',['~a~','~b~'],cb_make('bracket'),['ab','ba']);
cb_trial('empty-pattern-array',[],cb_make('fixed'),[12,true,false,null]);
cb_trial('empty-subject-array','~a~',cb_make('fixed'),[]);
cb_trial('limit-zero','~a~',cb_make('fixed'),'aaa',0);
cb_trial('limit-one','~a~',cb_make('fixed'),'aaa',1);
cb_trial('limit-negative2','~a~',cb_make('fixed'),'aaa',-2);
cb_trial('limit-long-min','~a~',cb_make('fixed'),'aaa',-PHP_INT_MAX-1);
cb_trial('limit-per-subject-pattern',['~a~','~b~'],cb_make('fixed'),['aabb','bbaa'],1);
cb_trial('empty-retry','~(?:|a)~',cb_make('fixed'),'a');
cb_trial('empty-lazy','~a*?~',cb_make('fixed'),'aa');
cb_trial('empty-keep','~a\K~',cb_make('fixed'),'aa');
cb_trial('utf-offset','~\p{L}~u',cb_make('fixed'),'Жé𐍈',-1,PREG_OFFSET_CAPTURE);
cb_trial('empty-utf','~~u',cb_make('fixed'),'Жé𐍈');
cb_trial('empty-crlf','~(*CRLF)(?:)~',cb_make('fixed'),"\r\n");
cb_trial('binary-nul','~\x00(.)~',cb_make('binary'),"a\x00\xffb\x00c",-1,PREG_OFFSET_CAPTURE);
cb_trial('unknown-flags','~a~',cb_make('fixed'),'a',-1,3);
cb_trial('negative-flags','~(a)(b)?~',cb_make('fixed'),'a',-1,-1);
cb_trial('return-int','~a~',cb_make('int'),'aa');
cb_trial('return-float','~a~',cb_make('float'),'a');
cb_trial('return-bool','~a~',cb_make('bool'),'a');
cb_trial('return-null','~a~',cb_make('null'),'a');
cb_trial('return-array-warning','~a~',cb_make('array'),'a');
cb_trial('return-stringable','~a~',cb_make('stringable'),'a');
cb_trial('return-object-error','~a~',cb_make('object'),'a');
cb_trial('return-throw','~a~',cb_make('throw'),'aa');
cb_trial('named-callable','~a~','cb_named','aba');
cb_trial('static-callable','~a~',['CbCallable','run'],'aba');
cb_trial('invokable-callable','~a~',new CbCallable(),'aba');
cb_trial('invalid-pattern','~(~',cb_make('fixed'),'a');
cb_trial('invalid-pattern-prefix',['~a~','~(~'],cb_make('fixed'),['first'=>'a','tail'=>'a']);
cb_trial('invalid-utf-partial','~a~u',cb_make('fixed'),['first'=>'a','bad'=>"\xff",'tail'=>'a']);
ca_trial('map-pipeline',['~a~'=>cb_make('fixed'),'~X~'=>cb_make('bracket')],'a');
ca_trial('map-subject-order',['~a~'=>cb_make('bracket'),'~b~'=>cb_make('fixed')],['ab','ba']);
ca_trial('map-limit-per-pattern',['~a~'=>cb_make('fixed'),'~b~'=>cb_make('bracket')],['aabb','bbaa'],1);
ca_trial('map-empty',[],[12,true,false,null,new CbString('a')]);
ca_trial('map-empty-subject',['~a~'=>cb_make('fixed')],[]);
ca_trial('map-offset-null',['~(?<n>a)(b)?~'=>cb_make('fixed')],'za',-1,PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
ca_trial('map-empty-retry',['~(?:|a)~'=>cb_make('fixed')],'a');
ca_trial('map-invalid-pattern-late',['~a~'=>cb_make('fixed'),'~(~'=>cb_make('fixed')],['a','b']);
ca_trial('map-invalid-callback-late',['~a~'=>cb_make('fixed'),'~b~'=>'not_a_callback'],'ab');
ca_trial('map-numeric-pattern-key',[123=>cb_make('fixed')],'123');
ca_trial('map-throw-late',['~a~'=>cb_make('fixed'),'~X~'=>cb_make('throw')],'a');
ca_trial('map-utf-error',['~a~u'=>cb_make('fixed')],['first'=>'a','bad'=>"\xff",'tail'=>'a']);
$f=new ReflectionFunction('preg_replace_callback');$params=[];foreach($f->getParameters()as $p)$params[]=[$p->getName(),(string)$p->getType(),$p->isPassedByReference(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];
cb_emit('reflection-callback',[(string)$f->getReturnType(),$f->getNumberOfRequiredParameters(),$f->getNumberOfParameters(),$params]);
$f=new ReflectionFunction('preg_replace_callback_array');$params=[];foreach($f->getParameters()as $p)$params[]=[$p->getName(),(string)$p->getType(),$p->isPassedByReference(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];
cb_emit('reflection-callback-array',[(string)$f->getReturnType(),$f->getNumberOfRequiredParameters(),$f->getNumberOfParameters(),$params]);
