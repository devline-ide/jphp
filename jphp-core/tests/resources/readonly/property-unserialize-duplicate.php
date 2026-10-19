<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;}echo serialize(ro_result(function(){return unserialize('O:1:"R":2:{s:1:"x";i:3;s:1:"x";i:4;}');}));
