<?php
require __DIR__ . "/helper.php";
#[Attribute] class A { public function __construct(public int $x, public string $y = 'default') {} }
#[A(x: '12')] function f() {}
$a=(new ReflectionFunction('f'))->getAttributes()[0]; emit('arguments', $a->getArguments()); attempt('instance', fn() => (array)$a->newInstance());
