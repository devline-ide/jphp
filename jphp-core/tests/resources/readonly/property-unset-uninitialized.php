<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;function drop(){unset($this->x);}function init(){$this->x=3;}}$r=new R;echo serialize([ro_result(function()use($r){unset($r->x);}),ro_result(function()use($r){$r->drop();}),ro_result(function()use($r){$r->init();}),$r->x]);
