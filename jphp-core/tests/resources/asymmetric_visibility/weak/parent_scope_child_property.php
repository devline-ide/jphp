<?php
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class A{function write($o){$o->x=7;return $o->x;}}class B extends A{public protected(set) int $x=1;}$b=new B;attempt("parent",fn()=>(new A)->write($b));
