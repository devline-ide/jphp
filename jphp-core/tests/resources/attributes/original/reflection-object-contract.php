<?php
require __DIR__ . "/helper.php";
#[Attribute] class A {} #[A] function f() {}
$a=(new ReflectionFunction('f'))->getAttributes()[0]; emit('name', [$a->name, $a->getName()]);
attempt('write-name', function() use ($a) { $a->name='Changed'; return [$a->name,$a->getName()]; });
attempt('clone', function() use ($a) { $b=clone $a; return $b->getName(); });
attempt('serialize', fn() => serialize($a)); attempt('string', fn() => (string)$a); attempt('construct', fn() => new ReflectionAttribute());
