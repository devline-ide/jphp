<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class P{function init(){ $this->x=3;}}class C extends P{public readonly int $x;}$c=new C;echo serialize([ro_result(function()use($c){$c->init();}),isset($c->x)]);
