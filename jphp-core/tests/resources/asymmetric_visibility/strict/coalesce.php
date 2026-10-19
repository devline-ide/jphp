<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
function rhs(){echo "rhs\n";return 7;}class H{public private(set) int $x=2;public private(set) ?int $n=null;public private(set) int $u;}$h=new H;attempt("existing",fn()=>$h->x??=rhs());attempt("null",fn()=>$h->n??=rhs());attempt("uninit",fn()=>$h->u??=rhs());echo "state:",$h->x,":",json_encode($h->n),"\n";
