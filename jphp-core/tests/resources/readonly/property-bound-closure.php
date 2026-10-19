<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{public readonly int $x;}$r=new R;$f=Closure::bind(function(){$this->x=3;},$r,R::class);echo serialize([ro_result($f),$r->x,ro_result($f)]);
