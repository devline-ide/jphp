<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly int $x=3){}function __clone(){$this->x=4;throw new Exception("clone-stop");}}$r=new R;echo serialize([ro_result(function()use($r){return clone $r;}),ro_result(function()use($r){$r->x=5;}),$r->x]);
