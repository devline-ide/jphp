<?php interface I {public int $x {get;}}interface J {public int $x {get;}}function enter(){class C implements I,J {}}enter();
