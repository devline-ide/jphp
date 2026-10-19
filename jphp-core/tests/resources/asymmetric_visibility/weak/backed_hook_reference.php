<?php
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H{public private(set) int $x=2{&get{echo "get\n";return $this->x;}}function &alias(){return $this->x;}}$h=new H;attempt("read",fn()=>$h->x);attempt("directref",function()use($h){$r=&$h->x;});$r=&$h->alias();$r=8;echo "state:",$h->x,"\n";
