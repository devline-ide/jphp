<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly ?int $x=null){}}$r=new R;echo serialize([ro_result(function()use($r){return $r->x??=3;}),$r->x]);
