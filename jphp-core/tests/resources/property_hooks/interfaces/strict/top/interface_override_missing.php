<?php declare(strict_types=1);interface I {public int $x {get;}}interface J extends I {public int $x {get;}}class C implements J {}
