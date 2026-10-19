<?php interface I {public int $x {get;}}interface J extends I {public int $x {set;}}function enter(){class C implements J {}}enter();
