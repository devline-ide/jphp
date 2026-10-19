<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly array $x=[1]){}}$r=new R;echo serialize([ro_result(function()use($r){$r->x[0]=2;}),ro_result(function()use($r){$r->x[]=3;}),ro_result(function()use($r){unset($r->x[0]);}),$r->x]);
