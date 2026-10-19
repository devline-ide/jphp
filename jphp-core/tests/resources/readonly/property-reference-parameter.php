<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
function take(&$v){$v=9;}class R{public readonly ?int $x;function alias(){take($this->x);}}$r=new R;echo serialize([ro_result(function()use($r){$r->alias();}),isset($r->x)]);
