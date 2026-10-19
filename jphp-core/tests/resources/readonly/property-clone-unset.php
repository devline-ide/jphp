<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly int $x=3){}function __clone(){unset($this->x);}function fill(){$this->x=4;}}$a=new R;$b=clone $a;echo serialize([$a->x,isset($b->x),ro_result(function()use($b){$b->fill();}),$b->x]);
