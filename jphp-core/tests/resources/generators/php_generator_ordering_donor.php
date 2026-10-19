<?php
trait ExternalOrderingTrait { function externalGenerator($seed = 13) { $sent = yield $this->offset + $seed; yield $this->offset + $seed + 1; return $sent + 901; } }
class ExternalOrderingDonor { public $offset = 5000; use ExternalOrderingTrait; }
function external_ordering_function($seed = 23) { $sent = yield $seed; yield $seed + 1; return $sent + 1001; }
$donorObject = new ExternalOrderingDonor();
return [$donorObject->externalGenerator(), external_ordering_function()];
