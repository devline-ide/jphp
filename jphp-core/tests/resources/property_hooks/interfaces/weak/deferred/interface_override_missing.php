<?php interface I {public int $x {get;}}interface J extends I {public int $x {get;}}function enter(){class C implements J {}}enter();
