<?php
require __DIR__."/helper.php";
#[Attribute] class Bag { public function __construct(public $closure, public $callable) {} }
#[Bag(static function(){return 11;}, strlen(...))] function sample() {}
$a=(new ReflectionFunction('sample'))->getAttributes()[0];$x=$a->getArguments();$y=$a->getArguments();emit('calls',[$x[0](),$x[1]('abc'),$x[0]===$y[0],$x[1]===$y[1]]);
class Scope { private const SECRET=9; #[Bag(static function(){return self::SECRET;},strlen(...))] public function method() {} }
$args=(new ReflectionMethod('Scope','method'))->getAttributes()[0]->getArguments();emit('private-scope',$args[0]());
