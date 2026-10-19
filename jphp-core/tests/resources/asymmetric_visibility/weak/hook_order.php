<?php
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
function rhs(){echo "rhs\n";return 7;}class H{public private(set) int $x=2{get{echo "get\n";return $this->x;}set{echo "set\n";$this->x=$value;}}function own(){$this->x+=3;return $this->x;}}$h=new H;attempt("read",fn()=>$h->x);attempt("assign",function()use($h){$h->x=rhs();});attempt("compound",function()use($h){$h->x+=rhs();});attempt("inc",function()use($h){$h->x++;});attempt("own",fn()=>$h->own());
