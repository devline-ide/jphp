<?php
declare(strict_types=1);
$rows=[];$effects=[];
function arg_effect($v){global $effects;$effects[]=$v;return $v;}
function run_arity($id,$operation){global $rows,$effects;$effects=[];try{$result=$operation();$rows[]=[$id,'OK',$result,$effects];}catch(Throwable $e){$rows[]=[$id,get_class($e),$e->getMessage(),$effects];}}
function plus_args($a,$b){return $a+$b;}
run_arity('direct-zero',fn()=>get_error_handler(arg_effect(1)));
$zero='get_error_handler';run_arity('dynamic-zero',fn()=>$zero(arg_effect(2)));
run_arity('direct-singular-few',fn()=>get_debug_type());
$fixed='get_debug_type';run_arity('dynamic-singular-many',fn()=>$fixed(arg_effect(3),arg_effect(4)));
run_arity('direct-singular-many',fn()=>get_debug_type(arg_effect(5),arg_effect(6)));
run_arity('direct-optional-few',fn()=>enum_exists());
$optional='enum_exists';run_arity('dynamic-optional-few',fn()=>$optional());
run_arity('direct-optional-many',fn()=>enum_exists(arg_effect('stdClass'),arg_effect(true),arg_effect(false)));
run_arity('dynamic-optional-many',fn()=>$optional(arg_effect('stdClass'),arg_effect(true),arg_effect(false)));
run_arity('direct-plural-few',fn()=>str_repeat());
$plural='str_repeat';run_arity('dynamic-plural-few',fn()=>$plural());
run_arity('direct-plural-many',fn()=>str_repeat(arg_effect('x'),arg_effect(2),arg_effect(3)));
run_arity('dynamic-plural-many',fn()=>$plural(arg_effect('x'),arg_effect(2),arg_effect(3)));
run_arity('direct-variadic-few',fn()=>call_user_func());
$variadic='call_user_func';run_arity('dynamic-variadic-few',fn()=>$variadic());
run_arity('direct-variadic-valid',fn()=>call_user_func('plus_args',arg_effect(2),arg_effect(3)));
run_arity('dynamic-variadic-valid',fn()=>$variadic('plus_args',arg_effect(4),arg_effect(5)));
run_arity('optional-valid',fn()=>enum_exists('stdClass'));
echo json_encode($rows,JSON_UNESCAPED_SLASHES),"\n";
