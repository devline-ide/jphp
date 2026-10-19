<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
readonly class P{public int $x;}function create(){readonly class C extends P{function __construct(){$this->x=3;}}return new C;}$r=create();echo serialize([$r->x,(new ReflectionClass(C::class))->isReadOnly(),ro_result(function()use($r){$r->x=4;})]);
