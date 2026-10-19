<?php
declare(strict_types=1);
class P{public int $x=2{get{echo "Pget;";return $this->x;}set(int $v){echo "Pset:$v;";$this->x=$v;}}}
class C extends P{public int $x{get{echo "Cget;";return parent::$x::get()*2;}set{echo "Cset;";parent::$x::set(v:arg($value+1));}}}
function arg($v){echo "arg:$v;";return $v;}
$c=new C;$c->x=5;echo "value:",$c->x,"\n";
class Plain{public int $y=1;}
class PlainChild extends Plain{public int $y=4{get{$backing=$this->y;return parent::$y::get()*2;}set{parent::$y::set(value:$value+1);}}}
$d=new PlainChild;$d->y=5;echo "plain:",$d->y,"\n";
