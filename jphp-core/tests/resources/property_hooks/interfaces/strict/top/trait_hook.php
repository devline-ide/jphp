<?php declare(strict_types=1);interface I {public int $x {get;set;}} trait T {public int $x=5 {get=>$this->x;set{$this->x=$value;}}} class C implements I {use T;} $c=new C;$c->x=6;echo $c->x;
