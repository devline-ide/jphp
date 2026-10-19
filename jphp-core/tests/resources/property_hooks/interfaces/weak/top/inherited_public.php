<?php interface I {public int $x {get;set;}} class A {public int $x=4;} class C extends A implements I {} echo (new C)->x;
