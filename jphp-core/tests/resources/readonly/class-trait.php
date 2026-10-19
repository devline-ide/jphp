<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
trait T{public readonly int $x;function init(){$this->x=3;}}readonly class R{use T;}$r=new R;$r->init();echo serialize([$r->x,(new ReflectionProperty(R::class,"x"))->isReadOnly()]);
