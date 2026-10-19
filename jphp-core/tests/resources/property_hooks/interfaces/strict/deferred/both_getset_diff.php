<?php declare(strict_types=1);class A{}class B extends A{} interface I {public B $x {get;set;}} function enter(){class C implements I {public A $x {get=>new A;set{}}}}enter();
