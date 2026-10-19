<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly int &$x){}}$v=3;echo serialize([ro_result(function()use(&$v){return new R($v);}),$v]);
