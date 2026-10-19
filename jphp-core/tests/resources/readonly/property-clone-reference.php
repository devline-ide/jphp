<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly int $x=3){}function __clone(){$v=&$this->x;}}$a=new R;echo serialize([ro_result(function()use($a){return clone $a;}),$a->x]);
