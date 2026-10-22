<?php
function row($id,$value){echo $id,':',json_encode($value,JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";}
ini_set('pcre.jit','0');ini_set('pcre.backtrack_limit','10');$m=['seed'];$r=preg_match('~^(a+)+$~',str_repeat('a',30).'!',$m);row('backtrack',[$r,$m,preg_last_error(),preg_last_error_msg()]);
ini_set('pcre.backtrack_limit','1000000');ini_set('pcre.recursion_limit','1');$r=preg_match('~(?<n>a(?&n)?b)~','aaaabbbb',$m);row('depth',[$r,$m,preg_last_error(),preg_last_error_msg()]);ini_set('pcre.recursion_limit','100000');
set_error_handler(function($n,$s){throw new RuntimeException('handler');});$m=['seed'];try{preg_match('~(~','a',$m);}catch(Throwable $e){row('warning-throw',[get_class($e),$e->getMessage(),$m,preg_last_error()]);}restore_error_handler();
$events=[];set_error_handler(function($n,$s)use(&$events){$before=preg_last_error();$ok=preg_match('~a~','a');$events[]=[$before,$ok,preg_last_error()];return true;});$r=preg_match('~(~','a',$m);restore_error_handler();row('warning-reentry',[$r,$m,$events,preg_last_error()]);
class TypedMatches {public string $text='seed';}$o=new TypedMatches();try{preg_match('~a~','a',$o->text);}catch(Throwable $e){row('typed-matches',[get_class($e),$e->getMessage(),$o->text,preg_last_error()]);}
$m=23;$r=preg_match('~a~','a',$m);row('scalar-matches',[$r,$m,preg_last_error()]);
$r=preg_match('~(*MARK:chosen)a~','a',$m,PREG_OFFSET_CAPTURE);row('mark',[$r,$m,preg_last_error()]);

$m=[];$r=preg_match('~(?<x>a)|(?<x>b)~J','a',$m,PREG_UNMATCHED_AS_NULL);row('duplicate-first',[$r,$m]);
$m=[];preg_match('~(?<n>a)~','a',$m,PREG_OFFSET_CAPTURE);$copy=$m;$copy['n'][0]='x';row('capture-cow',[$m,$copy]);
