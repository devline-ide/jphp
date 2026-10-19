<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
readonly class P{public int $x;}readonly class C extends P{public string $y;function __construct(){$this->x=3;$this->y="Y";}}$r=new C;echo serialize([$r->x,$r->y,(new ReflectionClass(C::class))->isReadOnly()]);
