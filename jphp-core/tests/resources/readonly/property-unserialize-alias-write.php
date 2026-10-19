<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;public $y;}$r=unserialize('O:1:"R":2:{s:1:"x";i:3;s:1:"y";R:2;}');$r->y=4;echo serialize([$r->x,$r->y,ro_result(function()use($r){$r->y=[];}),$r->x,$r->y,ro_result(function()use($r){$r->x=5;})]);
