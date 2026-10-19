<?php
declare(strict_types=1);
function ph_try($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$result=ph_try(function(){
class H {public array $events=[];public int $x=2 {get {$this->events[]='get';return $this->x*2;}}public int $v {get=>9;}}$h=new H;$vars=get_object_vars($h);$j=json_encode($h);$v=var_export($h,true);return [$vars,$j,$v,$h->events];
});restore_error_handler();echo json_encode(['result'=>$result,'warnings'=>$warnings],JSON_UNESCAPED_SLASHES|JSON_PARTIAL_OUTPUT_ON_ERROR),"\n";
