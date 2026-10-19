<?php
require __DIR__ . "/helper.php";
#[Attribute] class A { public function __construct(public $x) {} }
#[A(["\xff"=>"\xfe", "utf8"=>"Ж", "nul"=>"\0"])] function f() {}
$a=(new ReflectionFunction('f'))->getAttributes()[0]; $x=$a->getArguments(); emit('raw', $x); $x[0]['utf8']='changed'; emit('again', $a->getArguments());
