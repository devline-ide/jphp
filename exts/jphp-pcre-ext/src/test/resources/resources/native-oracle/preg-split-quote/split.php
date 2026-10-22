<?php
function psq_pack($v) {
    if (is_string($v)) return ['bytes',bin2hex($v)];
    if (is_array($v)) { $r=[]; foreach($v as $k=>$x)$r[]=[is_int($k)?['int',$k]:['bytes',bin2hex($k)],psq_pack($x)]; return ['array',$r]; }
    return $v;
}
function psq_emit($id,$v){echo $id,':',json_encode(psq_pack($v),JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";}
function ps_trial($id,$p,$s,$limit=-1,$flags=0) {
    preg_match('~.~u',"\xff");$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try{$r=preg_split($p,$s,$limit,$flags);$ex=null;}
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    psq_emit($id,[$r,preg_last_error(),preg_last_error_msg(),$events,$ex]);
}
function pq_trial($id,$s,$delimiter=null) {
    preg_match('~.~u',"\xff");$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try{$r=preg_quote($s,$delimiter);$ex=null;}
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally{restore_error_handler();}
    psq_emit($id,[$r,preg_last_error(),preg_last_error_msg(),$events,$ex]);
}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
ps_trial('literal','~,~','a,b,c');
ps_trial('no-match','~,~','abc');
ps_trial('empty-subject','~,~','');
ps_trial('leading-trailing','~,~',',a,,b,');
ps_trial('no-empty','~,~',',a,,b,',-1,PREG_SPLIT_NO_EMPTY);
ps_trial('capture','~(,)|;~',',a,,b;',-1,PREG_SPLIT_DELIM_CAPTURE);
ps_trial('offset','~(,)|;~',',a,,b;',-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('capture-no-empty','~(,)|;~',',a,,b;',-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_NO_EMPTY);
ps_trial('capture-offset','~(,)|;~',',a,,b;',-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('no-empty-offset','~(,)|;~',',a,,b;',-1,PREG_SPLIT_NO_EMPTY|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('all-flags','~(,)|;~',',a,,b;',-1,PREG_SPLIT_NO_EMPTY|PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('limit-zero','~,~','a,b,c',0);
ps_trial('limit-negative','~,~','a,b,c',-2);
ps_trial('limit-one','~(,)~',',a,b,',1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('limit-two','~(,)~','a,b,c',2,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('limit-three','~(,)~','a,b,c',3,PREG_SPLIT_DELIM_CAPTURE);
ps_trial('limit-no-empty','~,~',',,a,,b,c',2,PREG_SPLIT_NO_EMPTY|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('limit-tail-empty','~,~','a,,',2,PREG_SPLIT_NO_EMPTY|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('limit-long-max','~,~','a,b,c',PHP_INT_MAX);
ps_trial('limit-long-min','~,~','a,b,c',-PHP_INT_MAX-1);
ps_trial('limit-over-int32','~,~','a,b,c',4294967297);
ps_trial('limit-under-int32','~,~','a,b,c',-4294967295);
ps_trial('unknown-flag8','~,~','a,b',-1,8);
ps_trial('unknown-match-offset','~,~','a,b',-1,256);
ps_trial('unknown-unmatched-null','~,~','a,b',-1,512);
ps_trial('negative-flags','~(,)~',',a,,',-1,-1);
ps_trial('known-plus-unknown','~(,)~',',a,,',-1,PREG_SPLIT_DELIM_CAPTURE|8);
ps_trial('optional-capture','~(,)?;~','a;b,;c',-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('middle-unmatched','~(,)?(;)(:)?~','a;b,;c',-1,PREG_SPLIT_DELIM_CAPTURE);
ps_trial('empty-capture','~(())~','ab',-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('named-capture','~(?<separator>,)~','a,b,c',-1,PREG_SPLIT_DELIM_CAPTURE);
ps_trial('duplicate-capture','~(?<x>,)|(?<x>;)~J','a,b;c',-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('branch-reset','~(?|(,)|(;))~','a,b;c',-1,PREG_SPLIT_DELIM_CAPTURE);
ps_trial('empty-pattern','~~','ab',-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('empty-pattern-no-empty','~~','ab',-1,PREG_SPLIT_NO_EMPTY|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('empty-subject-empty-pattern','~~','',-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('empty-alternative','~(?:|a)~','a',-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('empty-alternative-limit','~(?:|a)~','a',2,PREG_SPLIT_NO_EMPTY|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('lazy-empty','~a*?~','aa',-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('greedy-empty','~a*~','aa',-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('keep','~a\K~','aa',-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('keep-capture','~(a)\K~','aa',-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('lookahead','~(?=a)~','aa',-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('crlf-empty','~(*CRLF)(?:)~',"\r\n",-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('crlf-empty-or-cr','~(*CRLF)(?:|\r)~',"\r\n",-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('crlf-multiline','~(*ANYCRLF)^|$~m',"a\r\nb\rc\n",-1,PREG_SPLIT_NO_EMPTY|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('utf-empty','~~u','Жé𐍈',-1,PREG_SPLIT_NO_EMPTY|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('utf-delimiter','~(é)~u','aéЖéz',-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('binary-delimiter','~\x00~',"a\x00\xff\x00z",-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('binary-capture',"~(\xff)~","a\xff\x00\xffz",-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('nul-pattern',"~(a\x00)~","xa\x00za\x00",-1,PREG_SPLIT_DELIM_CAPTURE|PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('bad-subject-utf','~.~u',"\xff");
ps_trial('bad-subject-utf-limit1','~.~u',"\xff",1);
ps_trial('bad-subject-utf-after-prefix','~,~u',"a,b,\xff",-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('bad-pattern-utf',"~\xff~u",'a');
ps_trial('invalid-pattern','~(~','a');
ps_trial('invalid-delimiter','abc','a');
ps_trial('invalid-modifier','~a~q','a');
ps_trial('invalid-pattern-limit1','~(~','a',1);
ps_trial('partial-backtrack','~(*NO_JIT)(*NO_START_OPT)(*LIMIT_MATCH=30)(?:x|(?:a+)+b)~','leftx '.str_repeat('a',30).'!',-1,PREG_SPLIT_OFFSET_CAPTURE);
ps_trial('partial-backtrack-limit1','~(*NO_JIT)(*NO_START_OPT)(*LIMIT_MATCH=30)(?:x|(?:a+)+b)~','leftx '.str_repeat('a',30).'!',1,PREG_SPLIT_OFFSET_CAPTURE);
psq_emit('arity-2',[preg_split('~,~','a,b'),preg_last_error()]);
psq_emit('arity-3',[preg_split('~,~','a,b,c',2),preg_last_error()]);
$events=[];set_error_handler(function($n,$s)use(&$events){$events[]=[preg_last_error(),preg_match('~a~','a'),preg_last_error()];return true;});$r=preg_split('~(~','a');restore_error_handler();
psq_emit('warning-reentry',[$r,$events,preg_last_error()]);
set_error_handler(function($n,$s){throw new RuntimeException('handler');});try{preg_split('~(~','a');$v=['not-thrown'];}catch(Throwable $e){$v=[get_class($e),$e->getMessage(),preg_last_error()];}restore_error_handler();
psq_emit('warning-throw',$v);
$f=new ReflectionFunction('preg_split');$params=[];foreach($f->getParameters()as $p)$params[]=[$p->getName(),(string)$p->getType(),$p->isPassedByReference(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];
psq_emit('reflection',[(string)$f->getReturnType(),$f->getNumberOfRequiredParameters(),$f->getNumberOfParameters(),$params]);
class PsString {public function __toString(){return 'a,b';}}
ps_trial('pattern-int',123,'123123');
ps_trial('pattern-null',null,'abc');
ps_trial('pattern-array',[],'abc');
ps_trial('subject-int','~2~',1212);
ps_trial('subject-bool','~1~',true);
ps_trial('subject-null','~~',null);
ps_trial('subject-stringable','~,~',new PsString());
ps_trial('subject-array','~,~',[]);
ps_trial('limit-string','~,~','a,b,c','2');
ps_trial('limit-float','~,~','a,b,c',2.5);
ps_trial('limit-null','~,~','a,b,c',null);
ps_trial('limit-array','~,~','a,b,c',[]);
ps_trial('flags-string','~,~',',a,',-1,'1');
ps_trial('flags-float','~,~',',a,',-1,1.5);
ps_trial('flags-null','~,~',',a,',-1,null);
ps_trial('flags-array','~,~','a,b',-1,[]);
ps_trial('argument-order','~(~',[],[],[]);
ps_trial('argument-order-pattern',[],[],[],[]);
