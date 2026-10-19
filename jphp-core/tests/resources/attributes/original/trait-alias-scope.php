<?php
require __DIR__ . "/helper.php";
#[Attribute] class Meta { public function __construct(public $values) {} }
class ParentC {}
trait T { #[Meta([self::class, parent::class, __CLASS__, __TRAIT__, __METHOD__])] public function original() {} }
class C extends ParentC { use T { original as alias; } }
foreach (['original', 'alias'] as $m) { $r = new ReflectionMethod('C', $m); emit('declaration', [$r->getName(), $r->getDeclaringClass()->getName()]); attempt($m, fn() => $r->getAttributes()[0]->getArguments()); }
