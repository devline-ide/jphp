<?php interface I {public int $x {get;}} interface J {public int $y {set;}} function enter(){class C implements I,J {}}enter();
