<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;function set($v){$this->x=$v;}}$r=new R;echo serialize([ro_result(function()use($r){$r->set([]);}),isset($r->x),ro_result(function()use($r){$r->set("3");}),$r->x]);
