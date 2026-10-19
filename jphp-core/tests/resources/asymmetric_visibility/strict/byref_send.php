<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
function change(&$v){echo "callee\n";$v=9;}function inspect($v){return $v;}class H{public private(set) int $x=2;function own(){change($this->x);}}$h=new H;attempt("value",fn()=>inspect($h->x));attempt("ref",function()use($h){change($h->x);});$h->own();echo "state:",$h->x,"\n";
