<?php
declare(strict_types=1);
class Grand{public int $x{get{echo "Grand;";return 3;}}}class P extends Grand{}
class C extends P{public int $x{get{echo "C;";return parent::$x::get(ignored());}}}
function ignored(){echo "arg;";return 9;}echo(new C)->x,"\n";
class R{public int $y=2{&get=>$this->y;}}
class S extends R{public int $y{&get=>parent::$y::get();}}
$s=new S;$s->y=4;$r=&$s->y;$r=7;echo "ref:",$s->y,"\n";
class Back{public int $z=2{get=>$this->z;set{$this->z=$value;}}}
trait T{public int $z{get=>parent::$z::get()*3;}}
class Uses extends Back{use T;}$u=new Uses;$u->z=4;echo "trait:",$u->z,"\n";
