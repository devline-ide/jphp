<?php
function sample($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$out=[];foreach(['get','set','GET','missing',0,1,1.5,true,false,null,[],new stdClass] as $value){$out[]=[get_debug_type($value),sample(fn()=>PropertyHookType::tryFrom($value)),sample(fn()=>PropertyHookType::from($value))];}
restore_error_handler();echo json_encode(['result'=>$out,'warnings'=>$warnings],JSON_UNESCAPED_SLASHES|JSON_PARTIAL_OUTPUT_ON_ERROR),"\n";
