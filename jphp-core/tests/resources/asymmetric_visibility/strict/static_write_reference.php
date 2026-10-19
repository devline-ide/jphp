<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H{public private(set) static int$x=2;public protected(set) static array$a=[1];static function &alias(){return self::$x;}static function own(){self::$x=3;self::$a[]=2;}}attempt("assign",function(){H::$x=7;});attempt("inc",function(){H::$x++;});attempt("compound",function(){H::$x+=2;});attempt("dim",function(){H::$a[]=2;});attempt("ref",function(){$r=&H::$x;});H::own();$r=&H::alias();$r=8;echo "state:",H::$x,":",json_encode(H::$a),"\n";$p=new ReflectionProperty(H::class,"x");echo "flags:",$p->getModifiers(),"\n";$p->setValue(null,9);echo "reflect:",H::$x,"\n";
