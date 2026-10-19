<?php declare(strict_types=1);class A{}class B extends A{}interface I {public B $x {get;}}function enter(){abstract class C implements I {abstract public A $x {get;}}}enter();
