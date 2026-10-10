<?php
function literalCall($a, $b = 5) { return $a + $b; }
function collectArgs(...$args) { return $args; }
function changeRef(&$a) { $a = 9; return $a; }
class CallTarget {
    public $value;
    function __construct($value) { $this->value = $value; }
    function add($value) { return $this->value + $value; }
    static function twice($value) { return $value * 2; }
}
$f = 'literalCall';
$closure = function($a) { return $a * 3; };
$object = new CallTarget(7,);
$ref = 1;
$set = 1;
$unset = 1;
$out = [literalCall(1,), literalCall(1, 2,), $f(2,), $closure(3,), $object->add(4,),
    CallTarget::twice(5,), strlen('abc',), collectArgs(1, 2,), collectArgs(...[3, 4],),
    changeRef($ref,), $ref, isset($set,), isset($set, $unset,), isset($missing,),
    literalCall(literalCall(1,),)];
unset($unset,);
$out[] = isset($unset,);
return serialize($out) . "\n";
