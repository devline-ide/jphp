<?php
function ph_try($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$result=ph_try(function(){
class H {public array $events=[];public int $x=1 {get {$this->events[]='g';return $this->x;}set {$this->events[]=['s',$value];$this->x=$value;}}}$h=new H;$a=$h->x++;$b=($h->x+=3);return [$a,$b,$h->x,$h->events];
});restore_error_handler();echo json_encode(['result'=>$result,'warnings'=>$warnings],JSON_UNESCAPED_SLASHES|JSON_PARTIAL_OUTPUT_ON_ERROR),"\n";
