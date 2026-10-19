<?php
declare(strict_types=1);
class A { public $x { get { throw new Exception("parent error"); } } } class B extends A { public $x { get { try { yield "before"; parent::$x::get(); } catch(Exception $e) { yield $e->getMessage(); } finally { echo "finally\n"; } return 6; } } } $g=(new B)->x; foreach($g as $v) echo "$v\n"; echo "ret:",$g->getReturn(),"\n";
