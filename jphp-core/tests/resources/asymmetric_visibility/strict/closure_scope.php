<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H{public private(set) int$x=1;function setter(){return function($h){$h->x=7;};}}$h=new H;$f=$h->setter();$f($h);echo "bound:",$h->x,"\n";$unbound=Closure::bind($f,null,null);attempt("unbound",fn()=>$unbound($h));
