<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public static $other;function __construct(public readonly int $x=3){}function __clone(){self::$other->x=4;}}$a=new R;R::$other=$a;echo serialize([ro_result(function()use($a){return clone $a;}),$a->x]);
