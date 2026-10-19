<?php declare(strict_types=1);class A{}class B extends A{} interface I {public A $x {set;}} class C implements I {public B $x {set(A $value){}}}
