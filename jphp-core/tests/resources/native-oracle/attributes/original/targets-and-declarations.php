<?php
require __DIR__ . "/helper.php";
#[Attribute(Attribute::TARGET_ALL | Attribute::IS_REPEATABLE)] class A {}
#[A] interface I {}
#[A] trait T {}
#[A] class C { #[A] public $p, $q; #[A] public const X = 1, Y = 2; #[A] public function m(#[A] $x) {} }
#[A] function f(#[A] $x) { yield $x; }
#[A] const G = 1;
$closure = #[A] static function(#[A] $x) { return $x; };
$arrow = #[A] static fn(#[A] $x) => $x;
$anonymous = new #[A] class {};
if (true) { #[A] class Later {} }
foreach ([new ReflectionClass('I'),new ReflectionClass('T'),new ReflectionClass('C'),new ReflectionProperty('C','p'),new ReflectionProperty('C','q'),new ReflectionClassConstant('C','X'),new ReflectionClassConstant('C','Y'),new ReflectionMethod('C','m'),new ReflectionParameter(['C','m'],'x'),new ReflectionFunction('f'),new ReflectionParameter('f','x'),new ReflectionConstant('G'),new ReflectionFunction($closure),new ReflectionFunction($arrow),new ReflectionClass($anonymous),new ReflectionClass('Later')] as $i => $r) { emit('target-' . $i, attribute_view($r)); }
