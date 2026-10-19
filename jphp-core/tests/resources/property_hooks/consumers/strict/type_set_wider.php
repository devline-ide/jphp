<?php
declare(strict_types=1);
function ph_try($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$result=ph_try(function(){
class H {public int $x=1 {get=>$this->x;set(int|string $v){$this->x=(int)$v;}}}$h=new H;$h->x='12';return [$h->x,ph_try(fn()=>($h->x=[]))];
});restore_error_handler();echo json_encode(['result'=>$result,'warnings'=>$warnings],JSON_UNESCAPED_SLASHES|JSON_PARTIAL_OUTPUT_ON_ERROR),"\n";
