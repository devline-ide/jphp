<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly array $x=[1]){}function __clone(){$this->x[0]=2;}}$a=new R;echo serialize([ro_result(function()use($a){return clone $a;}),$a->x]);
