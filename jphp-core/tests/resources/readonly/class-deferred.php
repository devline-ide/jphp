<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
function create(){if(true){readonly class R{public int $x;function __construct(){$this->x=3;}}}return new R;}$r=create();echo serialize([$r->x,(new ReflectionClass(R::class))->isReadOnly(),ro_result(function()use($r){$r->x=4;})]);
