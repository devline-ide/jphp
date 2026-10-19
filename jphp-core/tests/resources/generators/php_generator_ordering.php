<?php
function ordering_before($seed = 11) { $sent = yield $seed; yield $seed + 1; return $sent + 101; }
class OrderingClass {
    public $offset = 1000;
    function instance($seed = 21) { $sent = yield $this->offset + $seed; yield $this->offset + $seed + 1; return $sent + 201; }
    static function stat($seed = 31) { $sent = yield $seed; yield $seed + 1; return $sent + 301; }
}
trait OrderingTrait { function traitGenerator($seed = 41) { $sent = yield $this->offset + $seed; yield $this->offset + $seed + 1; return $sent + 401; } }
class OrderingConsumer { public $offset = 2000; use OrderingTrait { traitGenerator as aliasGenerator; } }
if (true) { class OrderingDeferred { public $offset = 3000; function deferred($seed = 51) { $sent = yield $this->offset + $seed; yield $this->offset + $seed + 1; return $sent + 501; } } }
$captured = 4000;
$orderingClosure = function ($seed = 61) use ($captured) { $sent = yield $captured + $seed; yield $captured + $seed + 1; return $sent + 601; };
function ordering_after($seed = 71) { $sent = yield $seed; yield $seed + 1; return $sent + 701; }
function &ordering_reference() { $value = 801; yield $value; return $value; }
function ordering_drive($name, $g) { echo $name, ':', $g->current(), ':', $g->send(7), ':'; $g->next(); echo $g->valid() ? 'valid' : 'done', ':', $g->getReturn(), "\n"; }
$object = new OrderingClass(); $consumer = new OrderingConsumer(); $deferred = new OrderingDeferred();
$objects = [
 'function-before' => ordering_before(),
 'class-instance' => $object->instance(),
 'class-static' => OrderingClass::stat(),
 'trait' => $consumer->traitGenerator(),
 'trait-alias' => $consumer->aliasGenerator(),
 'deferred' => $deferred->deferred(),
 'closure' => $orderingClosure(),
 'function-after' => ordering_after(),
];
foreach ($objects as $label => $generator) { ordering_drive($label, $generator); }
$reference = ordering_reference(); foreach ($reference as &$slot) { $slot = 999; } unset($slot);
echo 'reference:', $reference->getReturn(), "\n"; $objects['reference'] = $reference;
return $objects;
