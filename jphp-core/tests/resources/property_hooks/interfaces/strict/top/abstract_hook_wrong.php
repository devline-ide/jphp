<?php declare(strict_types=1);class A{}class B extends A{}interface I {public B $x {get;}}abstract class C implements I {abstract public A $x {get;}}
