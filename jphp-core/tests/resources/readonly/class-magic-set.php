<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
readonly class R{function __set($n,$v){echo "$n=$v|";}}$r=new R;$r->x=3;echo serialize($r);
