<?php
declare(strict_types=1);
class A { public $x { get { echo "parent\n"; return 7; } } } class B extends A { public $x { get { yield "first"; yield parent::$x::get(); yield parent::$x::get()+1; } } } foreach((new B)->x as $v) echo "$v\n";
