<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public function __construct(public readonly int $x=3){}function drop(){unset($this->x);}}$r=new R;echo serialize([ro_result(function()use($r){unset($r->x);}),ro_result(function()use($r){$r->drop();}),$r->x]);
