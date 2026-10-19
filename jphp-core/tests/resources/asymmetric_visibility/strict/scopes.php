<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class A{public private(set) int $x=1;public protected(set) int $y=2;function own($o){$o->x=3;$o->y=4;return [$o->x,$o->y];}}class B extends A{function child(){attempt("child_private",function(){$this->x=5;});$this->y=6;return $this->y;}}$a=new A;$b=new B;attempt("own_peer",fn()=>$a->own($b));attempt("child",fn()=>$b->child());attempt("global_protected",function()use($b){$b->y=7;});echo "state:",$b->x,":",$b->y,"\n";
