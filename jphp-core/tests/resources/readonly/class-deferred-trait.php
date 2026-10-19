<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
trait T{public readonly int $x;function init(){$this->x=3;}}function create(){readonly class R{use T;}return new R;}$r=create();$r->init();echo serialize([$r->x,(new ReflectionProperty(R::class,"x"))->isReadOnly(),ro_result(function()use($r){$r->x=4;})]);
