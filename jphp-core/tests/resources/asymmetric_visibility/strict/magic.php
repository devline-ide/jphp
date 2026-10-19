<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H{public private(set) int $x=2;function __set($n,$v){echo "magic-set:$n:$v\n";}function __unset($n){echo "magic-unset:$n\n";}function clear(){unset($this->x);}}$h=new H;attempt("initialized_assign",function()use($h){$h->x=3;});attempt("initialized_unset",function()use($h){unset($h->x);});$h->clear();attempt("unset_assign",function()use($h){$h->x=4;});attempt("unset_unset",function()use($h){unset($h->x);});
