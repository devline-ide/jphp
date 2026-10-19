<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class P{public readonly int $x;}class C extends P{function init($o){$o->x=4;}}class D extends P{}$c=new C;$d=new D;echo serialize([ro_result(function()use($c,$d){$c->init($d);}),$d->x]);
