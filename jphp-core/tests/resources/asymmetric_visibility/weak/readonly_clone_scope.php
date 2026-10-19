<?php
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class A{public private(set) readonly int$x;public readonly int$y;function __construct(){$this->x=1;$this->y=2;}}class B extends A{function __clone(){attempt("private",function(){$this->x=7;});$this->y=8;}}$b=new B;$c=clone$b;echo "state:",$b->x,":",$b->y,":",$c->x,":",$c->y,"\n";
