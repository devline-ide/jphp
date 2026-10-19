<?php
require __DIR__ . "/helper.php";
#[Attribute] class A { public function __construct(public $a, public $b, public $c) {} }
#[A(static function() { return 11; }, static fn() => 12, strlen(...))] function f() {}
$a=(new ReflectionFunction('f'))->getAttributes()[0]; $x=$a->getArguments(); $y=$a->getArguments(); emit('calls', [$x[0](), $x[1](), $x[2]('abc'), $x[0] === $y[0]]);
