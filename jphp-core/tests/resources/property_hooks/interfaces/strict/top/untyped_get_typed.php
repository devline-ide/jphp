<?php declare(strict_types=1);interface I {public $x {get;}}class C implements I {public int $x=2;}echo (new C)->x;
