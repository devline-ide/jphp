<?php interface I {public int $x {get;set;}}abstract class C implements I {public int $x {get=>2;}} class D extends C {public int $x {set{}}}
