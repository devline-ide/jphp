<?php class A{}class B extends A{}interface I {public B $x {get;}}class P {public A $x {get=>new A;}}class C extends P implements I {}
