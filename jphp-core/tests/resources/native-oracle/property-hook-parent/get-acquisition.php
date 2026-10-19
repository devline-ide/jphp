<?php
class P{public int $x{get=>2;}}class C extends P{public int $x{get{$f=parent::$x::get(...);return$f();}}}echo(new C)->x;
