<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;}$r=new R;echo serialize(ro_result(function()use($r){$r->x=3;}));
