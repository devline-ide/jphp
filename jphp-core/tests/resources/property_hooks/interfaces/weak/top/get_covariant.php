<?php class A{}class B extends A{} interface I {public A $x {get;}} class C implements I {public B $x {get=>new B;}} echo get_class((new C)->x);
