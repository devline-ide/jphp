<?php declare(strict_types=1);class A{}class B extends A{} interface I {public B $x {set;}} class C implements I {public A $x {set{}}} echo "ok";
