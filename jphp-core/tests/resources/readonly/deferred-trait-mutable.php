<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
trait T{public int $x;}function create(){readonly class R{use T;}return new R;}create();
