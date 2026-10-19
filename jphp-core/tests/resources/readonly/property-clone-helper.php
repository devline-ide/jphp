<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly int $x=3){}function change(){$this->x=4;}function __clone(){$this->change();}}$a=new R;$b=clone $a;echo serialize([$a->x,$b->x]);
