<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly ?int $x;}$r=new R;echo serialize([isset($r->x),empty($r->x),ro_result(function()use($r){return $r->x;})]);
