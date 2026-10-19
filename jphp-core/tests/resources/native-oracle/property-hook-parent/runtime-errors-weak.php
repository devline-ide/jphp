<?php
class P0{}class C0 extends P0{public int $x{get=>parent::$x::get();}}
class P1{private int $x=2{get=>$this->x;}}class C1 extends P1{public int $x{get=>parent::$x::get();}}
class P2{public int $x{get=>2;}}class C2 extends P2{public int $x{set{parent::$x::set($value);}}}
class P3{public int $x=2;}class C3 extends P3{public int $x{get=>parent::$x::get();}}
class P4{public int $x=2;}class C4 extends P4{public int $x=4{set(int|string $value){$backing=$this->x;parent::$x::set($value);}}}
class P5{public int $x{get=>2;}}class C5 extends P5{public int $x{set(int|string $value){parent::$x::set($value);}}}
foreach([function(){return(new C0)->x;},function(){return(new C1)->x;},function(){$c=new C2;$c->x=3;},function(){return(new C3)->x;},function(){$c=new C4;$c->x='oops';},function(){$c=new C5;$c->x='oops';}] as $f)
try{$f();echo "unexpected;\n";}catch(Throwable $e){echo get_class($e),'|',$e->getMessage(),"\n";}
try{$c=new C4;$c->x='6';echo "numeric|",$c->x,"\n";}catch(Throwable $e){echo get_class($e),'|',$e->getMessage(),"\n";}
