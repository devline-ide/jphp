<?php
function ph_try($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$result=ph_try(function(){
class H {final public int $x=1 {get=>$this->x;set {$this->x=$value;}}}$r=new ReflectionProperty(H::class,'x');return [$r->isFinal(),$r->getHook(PropertyHookType::Get)->isFinal(),$r->getHook(PropertyHookType::Set)->isFinal()];
});restore_error_handler();echo json_encode(['result'=>$result,'warnings'=>$warnings],JSON_UNESCAPED_SLASHES|JSON_PARTIAL_OUTPUT_ON_ERROR),"\n";
