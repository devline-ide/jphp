<?php
declare(strict_types=1);
function ph_try($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
class P {public int $x {final get=>1;}}class H extends P {public int $x {get=>2;}}
