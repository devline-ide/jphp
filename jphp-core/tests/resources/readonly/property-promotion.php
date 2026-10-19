<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(public readonly int $x=3){}}$r=new R;$p=new ReflectionProperty(R::class,"x");echo serialize([$r->x,$p->isPromoted(),$p->isReadOnly(),$p->hasDefaultValue(),ro_result(function()use($r){$r->__construct(4);}),$r->x]);
