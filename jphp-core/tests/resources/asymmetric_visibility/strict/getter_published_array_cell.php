<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H{public private(set) array$a=[1]{&get{echo "get\n";return $this->a;}}}$h=new H;attempt("dim",function()use($h){$h->a[0]=7;});attempt("append",function()use($h){$h->a[]=8;});attempt("unsetdim",function()use($h){unset($h->a[0]);});attempt("unsetproperty",function()use($h){unset($h->a);});echo "state:",json_encode($h->a),"\n";
