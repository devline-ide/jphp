<?php interface I {public int $x {get;set;}} abstract class A implements I {} class C extends A {public int $x=3;} echo (new C)->x;
