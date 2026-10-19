<?php class A{}class B extends A{}interface I {public A $x {get;}}interface J extends I {public B $x {get;}}class C implements J {public B $x {get=>new B;}}echo get_class((new C)->x);
