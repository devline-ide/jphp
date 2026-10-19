<?php
declare(strict_types=1);
function ph_try($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$result=ph_try(function(){
class H {public int $x=0 {get=>$this->x;set {$this->x=$value;}}}$h=new H;return [ph_try(fn()=>($h->x='12')),ph_try(fn()=>$h->x)];
});restore_error_handler();echo json_encode(['result'=>$result,'warnings'=>$warnings],JSON_UNESCAPED_SLASHES|JSON_PARTIAL_OUTPUT_ON_ERROR),"\n";
