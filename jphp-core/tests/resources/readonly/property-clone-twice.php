<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly int $x=3){}function __clone(){$this->x=4;$this->x=5;}}$a=new R;echo serialize([ro_result(function()use($a){return clone $a;}),$a->x]);
