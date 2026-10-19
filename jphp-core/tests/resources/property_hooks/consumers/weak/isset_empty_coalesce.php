<?php
function ph_try($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$result=ph_try(function(){
class H {public array $events=[];public ?int $x {get {$this->events[]='get';return null;}}}$h=new H;return [isset($h->x),empty($h->x),$h->x??9,$h->events];
});restore_error_handler();echo json_encode(['result'=>$result,'warnings'=>$warnings],JSON_UNESCAPED_SLASHES|JSON_PARTIAL_OUTPUT_ON_ERROR),"\n";
