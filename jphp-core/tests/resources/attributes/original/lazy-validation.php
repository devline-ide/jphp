<?php
require __DIR__ . "/helper.php";
class Plain {} #[Attribute(Attribute::TARGET_METHOD)] class MethodOnly {} #[Attribute] class Single { public function __construct(int $v) {} }
#[Missing, Plain, MethodOnly, Single(1), Single(2)] function f() {}
$r = new ReflectionFunction('f'); emit('metadata', attribute_view($r));
foreach ($r->getAttributes() as $a) attempt($a->getName(), fn() => get_class($a->newInstance()));
