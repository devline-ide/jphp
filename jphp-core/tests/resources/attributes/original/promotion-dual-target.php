<?php
require __DIR__ . "/helper.php";
#[Attribute(Attribute::TARGET_PARAMETER)] class ParameterOnly {}
#[Attribute(Attribute::TARGET_PROPERTY)] class PropertyOnly {}
#[Attribute(Attribute::TARGET_ALL)] class Both { public function __construct(public $v) {} }
class C { public function __construct(#[ParameterOnly, PropertyOnly, Both('v')] public int $p = 1) {} }
foreach ([new ReflectionParameter(['C','__construct'], 'p'), new ReflectionProperty('C','p')] as $r) {
 emit('promoted', [$r->isPromoted(), attribute_view($r)]);
 foreach ($r->getAttributes() as $a) attempt($a->getName() . '-' . $a->getTarget(), fn() => get_class($a->newInstance()));
}
