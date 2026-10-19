<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H { public private(set) int $i=2; public private(set) array $a=[1]; public private(set) object $o; function __construct(){$this->o=(object)["v"=>1];} } $h=new H;
attempt("read",fn()=>$h->i);attempt("assign",function()use($h){$h->i=3;});attempt("inc",function()use($h){$h->i++;});attempt("compound",function()use($h){$h->i+=3;});attempt("arraydim",function()use($h){$h->a[0]=3;});attempt("append",function()use($h){$h->a[]=3;});attempt("arrayunset",function()use($h){unset($h->a[0]);});attempt("reference",function()use($h){$r=&$h->i;});attempt("refbind",function()use($h){$r=3;$h->i=&$r;});attempt("unset",function()use($h){unset($h->i);});attempt("object_inner",function()use($h){$h->o->v=3;return $h->o->v;});echo "state:",$h->i,":",json_encode($h->a),"\n";
