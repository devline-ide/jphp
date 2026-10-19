<?php
require __DIR__ . "/helper.php";
#[Attribute] class A {}
#[A] class P { #[A] public $p; #[A] public const K=1; #[A] public function m() {} }
class C extends P {} class D extends P { public function m() {} }
foreach ([new ReflectionClass('C'),new ReflectionMethod('C','m'),new ReflectionProperty('C','p'),new ReflectionClassConstant('C','K'),new ReflectionMethod('D','m'),new ReflectionFunction((new C)->m(...))] as $i => $r) emit('origin-' . $i, attribute_view($r));
