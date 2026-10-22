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
$all='';for($i=0;$i<256;$i++)$all.=chr($i);
pq_trial('all-256-bytes',$all);
pq_trial('all-256-bytes-delimiter',$all,$all);
pq_trial('all-metacharacters',".\\+*?[^]$(){}=!<>|:-#");
pq_trial('ordinary-and-slash','AZaz09_ / @%&,;`~');
pq_trial('empty','');
pq_trial('nul',"a\x00b\xff");
pq_trial('delimiter-null','a/b#c',null);
pq_trial('delimiter-empty','a/b#c','');
pq_trial('delimiter-slash','a/b/c','/');
pq_trial('delimiter-hash','a#b/c','#');
pq_trial('delimiter-ordinary','abcabc','a');
pq_trial('delimiter-multiplebytes','abcabc','ab');
pq_trial('delimiter-multiplebytes-reversed','abcabc','ba');
pq_trial('delimiter-multibyte','éЖé','é');
pq_trial('delimiter-byte',"\xffx\xff","\xff");
pq_trial('delimiter-nul',"a\x00b", "\x00");
pq_trial('delimiter-nul-prefix',"a\x00b", "\x00a");
pq_trial('delimiter-metachar','[[a]]','[');
pq_trial('delimiter-invalid-utf',"\xff\xfe\xff","\xff\xfe");
pq_trial('quote-no-utf-validation',"\xc0\xaf\x80",'u');
psq_emit('arity-1',[preg_quote('a/b.c'),preg_last_error()]);
$f=new ReflectionFunction('preg_quote');$params=[];foreach($f->getParameters()as $p)$params[]=[$p->getName(),(string)$p->getType(),$p->isPassedByReference(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];
psq_emit('reflection',[(string)$f->getReturnType(),$f->getNumberOfRequiredParameters(),$f->getNumberOfParameters(),$params]);
class PqString {public function __toString(){return 'a.b';}}
pq_trial('string-int',123);
pq_trial('string-float',12.5);
pq_trial('string-bool',true);
pq_trial('string-null',null);
pq_trial('string-stringable',new PqString());
pq_trial('string-array',[]);
pq_trial('delimiter-int','123123',12);
pq_trial('delimiter-float','12.5',12.5);
pq_trial('delimiter-bool','a1a',true);
pq_trial('delimiter-null-allowed','a.b',null);
pq_trial('delimiter-stringable','a.b',new PqString());
pq_trial('delimiter-array','a.b',[]);
pq_trial('argument-order',[],[]);
