<?php
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
function rhs(){echo "rhs\n";return 7;}class H{public private(set) array$a=["x"=>2];}$h=new H;attempt("existing",fn()=>$h->a["x"]??=rhs());attempt("missing",fn()=>$h->a["y"]??=rhs());echo json_encode($h->a),"\n";
