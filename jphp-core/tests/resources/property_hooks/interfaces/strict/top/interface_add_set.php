<?php declare(strict_types=1);interface I {public int $x {get;}}interface J extends I {public int $x {set;}}class C implements J {public int $x=9;}echo (new C)->x;
