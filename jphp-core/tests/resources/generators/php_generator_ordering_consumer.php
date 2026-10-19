<?php
function consumer_ordering_before($seed = 33) { $sent = yield $seed; yield $seed + 1; return $sent + 1101; }
class ExternalOrderingConsumer { public $offset = 6000; use ExternalOrderingTrait { externalGenerator as externalAlias; } }
$captured = 7000;
$consumerClosure = function ($seed = 43) use ($captured) { $sent = yield $captured + $seed; yield $captured + $seed + 1; return $sent + 1201; };
function consumer_ordering_after($seed = 53) { $sent = yield $seed; yield $seed + 1; return $sent + 1301; }
$consumerObject = new ExternalOrderingConsumer();
return [$consumerObject->externalGenerator(), $consumerObject->externalAlias(), consumer_ordering_before(), $consumerClosure(), consumer_ordering_after()];
