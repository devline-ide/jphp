<?php
declare(strict_types=1);
class A { public $x { get { yield "parent"; return 7; } } } class B extends A { public $x { get { $p=parent::$x::get(); yield $p->current(); $p->next(); yield $p->getReturn(); } } } foreach((new B)->x as $v) echo "$v\n";
