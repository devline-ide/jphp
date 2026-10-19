<?php declare(strict_types=1);interface I {public int $x {get;}}interface J extends I {} function enter(){class C implements J {}}enter();
