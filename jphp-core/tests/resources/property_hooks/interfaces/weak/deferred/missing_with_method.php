<?php interface I {public int $x {get;set;}}abstract class A {abstract function f();}function enter(){class C extends A implements I {}}enter();
