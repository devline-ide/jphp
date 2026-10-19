<?php
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
function receiver($h){echo "receiver\n";return$h;}function rhs(){echo "rhs\n";return [];}class H{public private(set) int$x=2;}$h=new H;attempt("assign",function()use($h){receiver($h)->x=rhs();});attempt("compound",function()use($h){receiver($h)->x+=rhs();});
