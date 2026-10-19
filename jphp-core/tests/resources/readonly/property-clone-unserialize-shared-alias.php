<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;public $y;function __clone(){$this->x=4;}}$r=unserialize('O:1:"R":2:{s:1:"x";i:3;s:1:"y";R:2;}');$c=clone $r;echo serialize([$r->x,$r->y,$c->x,$c->y]);
