<?php
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H{public private(set) ?int$x;function &alias(){return$this->x;}}$h=new H;attempt("foreignref",function()use($h){$r=&$h->x;});$p=new ReflectionProperty(H::class,"x");echo "initialized:",(int)$p->isInitialized($h),"\n";$r=&$h->alias();echo "own:",(int)$p->isInitialized($h),":",json_encode($h->x),"\n";$r=7;echo "escaped:",$h->x,"\n";
