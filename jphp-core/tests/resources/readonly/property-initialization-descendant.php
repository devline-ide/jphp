<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class P{public readonly int $x;}class C extends P{function init(){ $this->x=3;}}$c=new C;$c->init();echo serialize([$c->x]);
