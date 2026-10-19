<?php
require __DIR__ . "/helper.php";
#[Attribute] class Base {} class Child extends Base {}
#[Child] function f() {} attempt('unmarked-subclass', fn() => get_class((new ReflectionFunction('f'))->getAttributes()[0]->newInstance()));
foreach ([-1, 0, Attribute::TARGET_ALL, 1<<20] as $flags) attempt('flags-' . $flags, fn() => (new Attribute($flags))->flags);
emit('constants', [Attribute::TARGET_CLASS,Attribute::TARGET_FUNCTION,Attribute::TARGET_METHOD,Attribute::TARGET_PROPERTY,Attribute::TARGET_CLASS_CONSTANT,Attribute::TARGET_PARAMETER,Attribute::TARGET_CONSTANT,Attribute::TARGET_ALL,Attribute::IS_REPEATABLE,ReflectionAttribute::IS_INSTANCEOF]);
