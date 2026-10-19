<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;function __unserialize(array $v):void{$this->x=$v['x'];}}$r=unserialize('O:1:"R":1:{s:1:"x";i:3;}');echo serialize([$r->x,ro_result(function()use($r){$r->x=4;})]);
