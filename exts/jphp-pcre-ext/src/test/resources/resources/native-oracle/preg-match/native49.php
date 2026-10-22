<?php
function packv($v) {
    if (is_string($v)) return ['bytes',bin2hex($v)];
    if (is_array($v)) { $r=[]; foreach($v as $k=>$x)$r[]=[is_int($k)?['int',$k]:['bytes',bin2hex($k)],packv($x)]; return ['array',$r]; }
    return $v;
}
function trial($id,$p,$s,$flags=0,$offset=0) {
    $m=['sentinel'];$events=[];
    set_error_handler(function($n,$t)use(&$events){$events[]=[$n,$t];return true;});
    try { $r=preg_match($p,$s,$m,$flags,$offset);$ex=null; }
    catch(Throwable $e){$r=null;$ex=[get_class($e),$e->getMessage()];}
    finally { restore_error_handler(); }
    echo $id,':',json_encode([packv($r),packv($m),preg_last_error(),preg_last_error_msg(),$events,$ex],JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";
}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
trial('literal','~(foo)(bar)~','xxfoobar!');
trial('no-match','~(foo)~','bar');
trial('empty','~~','');
trial('optional-middle','~(a)(b)?(c)~','ac');
trial('optional-tail','~(a)(b)?(c)?~','a');
trial('optional-null','~(a)(b)?(c)?~','a',PREG_UNMATCHED_AS_NULL);
trial('optional-offset','~(a)(b)?(c)?~','za',PREG_OFFSET_CAPTURE);
trial('optional-offset-null','~(a)(b)?(c)?~','za',PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
trial('named','~(?<word>foo)(?<tail>bar)?~','xxfoo',PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
trial('duplicate-name','~(?<x>a)|(?<x>b)~J','b',PREG_UNMATCHED_AS_NULL);
trial('branch-reset','~(?|(a)|(b))~','b');
trial('keep','~foo\Kbar~','foobar',PREG_OFFSET_CAPTURE);
trial('recursive','~^(?<n>\((?:[^()]++|(?&n))*\))$~','(a(b)c)');
trial('conditional','~^(a)?(?(1)b|c)$~','c');
trial('lookbehind','~(?<=foo)bar~','foobar');
trial('utf8','~(?<s>\p{L}+)~u','!Жé',PREG_OFFSET_CAPTURE);
trial('binary-byte','~(.)~',"\xff\x00",PREG_OFFSET_CAPTURE);
trial('binary-nul','~\x00(.)~',"x\x00\xff",PREG_OFFSET_CAPTURE);
trial('pattern-byte',"~\xff~","x\xff",PREG_OFFSET_CAPTURE);
trial('pattern-nul',"~a\x00b~","xa\x00b",PREG_OFFSET_CAPTURE);
trial('bad-subject-utf8','~.~u',"\xff");
trial('bad-pattern-utf8',"~\xff~u",'x');
trial('bad-offset-utf8','~.~u','Ж',PREG_OFFSET_CAPTURE,1);
trial('offset-bytes','~.~u','aЖz',PREG_OFFSET_CAPTURE,3);
trial('negative-offset','~.~','abcd',PREG_OFFSET_CAPTURE,-2);
trial('offset-past','~.~','abc',0,5);
trial('offset-negative-past','~.~','abc',PREG_OFFSET_CAPTURE,-9);
trial('anchor-offset','~^b~','ab',0,1);
trial('delimiter-paired','{a{2}}','aa');
trial('delimiter-escaped','~a\~b~','a~b');
trial('mod-i','~abc~i','ABC');
trial('mod-ms','~^a.b$~ms',"x\na\nb\ny");
trial('mod-x','~a \x20 b~x','a b');
trial('mod-n','~(a)(?<x>b)~n','ab');
trial('mod-D','~a$~D',"a\n");
trial('mod-U','~a.*b~U','a1b2b');
trial('invalid-pattern','~(~','x');
trial('invalid-delimiter','abc','x');
trial('missing-delimiter','~abc','x');
trial('invalid-modifier','~a~q','a');
trial('invalid-flags','~a~','a',1);
trial('negative-flags','~a~','a',-1);
trial('array-pattern',[], 'a');
trial('array-subject','~a~',[]);
$shared=null;$alias=&$shared;$r=preg_match('~(?<n>a)~','a',$shared);echo 'matches-alias:',json_encode([$r,packv($shared),packv($alias)]),"\n";
foreach(['preg_match','preg_last_error','preg_last_error_msg']as $name){$f=new ReflectionFunction($name);$params=[];foreach($f->getParameters()as $p)$params[]=[$p->getName(),(string)$p->getType(),$p->isPassedByReference(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];echo 'reflection-'.$name,':',json_encode([(string)$f->getReturnType(),$f->getNumberOfRequiredParameters(),$f->getNumberOfParameters(),$params]),"\n";}
