<?php interface I {public int $x {&get;}} class C implements I {public int $x=7;} $c=new C;$r=&$c->x;$r=8;echo $c->x;
