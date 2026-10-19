<?php
declare(strict_types=0);
function attempt($name,$f){try{$v=$f();echo$name,":ok:",json_encode($v),"\n";}catch(Throwable$e){echo$name,":",get_class($e),":",$e->getMessage(),"\n";}}
class O implements ArrayAccess {public array $a=["x"=>1];public function offsetExists(mixed$o):bool{return isset($this->a[$o]);}public function offsetGet(mixed$o):mixed{return$this->a[$o];}public function offsetSet(mixed$o,mixed$v):void{$this->a[$o]=$v;}public function offsetUnset(mixed$o):void{echo"offset-unset;";unset($this->a[$o]);}}
class U {public private(set) array$a;public private(set) object$o;public private(set) array$nested;public private(set) ?array$n=null;public private(set) bool$b=false;public private(set) int$i=2;public private(set) string$s="abc";function __construct(){$this->o=new O;$this->nested=[new O];}function own(){unset($this->a[0]);return"done";}}
$u=new U;attempt("outside-uninit",function()use($u){unset($u->a[0]);});attempt("own-uninit",fn()=>$u->own());attempt("object-offset",function()use($u){unset($u->o["x"]);return$u->o->a;});attempt("nested-object-offset",function()use($u){unset($u->nested[0]["x"]);return$u->nested[0]->a;});
foreach(["n","b","i","s"]as$p)attempt("scalar-".$p,function()use($u,$p){unset($u->$p[0]);});
