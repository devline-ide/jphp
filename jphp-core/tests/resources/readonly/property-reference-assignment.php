<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;function alias(&$v){$this->x=&$v;}}$r=new R;$v=3;echo serialize([ro_result(function()use($r,&$v){$r->alias($v);}),isset($r->x),$v]);
