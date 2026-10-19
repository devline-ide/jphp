<?php
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H {public private(set) int $x=2;public private(set) array $a=[1];function &alias(){return $this->x;}function &arrayAlias(){return $this->a;}function rebind(&$v){$this->x=&$v;} }$h=new H;$r=&$h->alias();$r=7;echo "escaped:",$h->x,"\n";attempt("type",function()use(&$r){$r=[];});$a=&$h->arrayAlias();$a[]=9;echo "array:",json_encode($h->a),"\n";$external=8;$h->rebind($external);$external=10;echo "rebound:",$h->x,":old:",$r,"\n";
