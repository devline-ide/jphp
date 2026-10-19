<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly object $x){}}$r=new R(new stdClass);$r->x->v=3;echo serialize([$r->x->v,ro_result(function()use($r){$r->x=new stdClass;})]);
