<?php
$crossRows = [
    'donor-trait' => new ReflectionMethod('ExternalOrderingTrait', 'externalGenerator'),
    'donor-class' => new ReflectionMethod('ExternalOrderingDonor', 'externalGenerator'),
    'consumer-trait' => new ReflectionMethod('ExternalOrderingConsumer', 'externalGenerator'),
    'consumer-alias' => new ReflectionMethod('ExternalOrderingConsumer', 'externalAlias'),
    'consumer-ordinary' => new ReflectionMethod('ExternalOrderingConsumer', 'externalOrdinary'),
    'donor-function' => new ReflectionFunction('external_ordering_function'),
    'consumer-before' => new ReflectionFunction('consumer_ordering_before'),
    'consumer-after' => new ReflectionFunction('consumer_ordering_after'),
    'consumer-closure' => new ReflectionFunction($consumerClosure),
];
foreach ($crossRows as $label => $reflection) { echo $label, ':', $reflection->isGenerator() ? 'true' : 'false', ':', gettype($reflection->isGenerator()), "\n"; }
