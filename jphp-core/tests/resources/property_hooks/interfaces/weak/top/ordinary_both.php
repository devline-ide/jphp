<?php interface I {public int $x {get;set;}} class C implements I {public int $x=2;} echo (new C)->x;
