<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
trait T{public readonly int $x;}function create(){class R{use T;public int $x;}return new R;}create();
