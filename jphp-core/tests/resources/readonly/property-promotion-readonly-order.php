<?php function ro_result($f){try{return ["ok",$f()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
class R{function __construct(readonly public int $x=3,readonly int $y=4){}}$r=new R;echo serialize([$r->x,$r->y,(new ReflectionProperty(R::class,"x"))->isPromoted(),(new ReflectionProperty(R::class,"y"))->isReadOnly()]);
