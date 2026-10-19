<?php
require __DIR__ . "/helper.php";
#[Attribute] class Audit { public function __construct(public $a, public $b, public $c = 'default') { $GLOBALS['events'][] = 'audit'; } }
class Payload { public function __construct(public $label) { $GLOBALS['events'][] = $label; } }
#[Audit(new Payload('a'), b: new Payload('b'))] function sample() {}
$events = [];
$a = (new ReflectionFunction('sample'))->getAttributes()[0]; emit('enumerated', $events);
$one = $a->getArguments(); emit('first', [$events, array_keys($one), $one[0]->label, $one['b']->label]);
$two = $a->getArguments(); emit('second', [$events, $one[0] === $two[0]]);
$obj = $a->newInstance(); emit('instance', [$events, $obj->c]);
emit('instances-distinct', $obj !== $a->newInstance());
