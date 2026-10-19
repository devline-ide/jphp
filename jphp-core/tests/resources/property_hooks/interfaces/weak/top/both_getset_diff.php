<?php class A{}class B extends A{} interface I {public B $x {get;set;}} class C implements I {public A $x {get=>new A;set{}}}
