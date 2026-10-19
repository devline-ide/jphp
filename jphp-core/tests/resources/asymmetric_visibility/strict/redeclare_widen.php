<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class A{public protected(set) int$x=1;}class B extends A{public int$x=2;} $b=new B;$b->x=3;echo $b->x,"\n";
