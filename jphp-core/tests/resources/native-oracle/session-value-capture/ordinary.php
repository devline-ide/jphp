<?php
class CaptureOrdinaryTarget { public int $x = 7; }
class CaptureOrdinaryValue {}
$value = new CaptureOrdinaryValue;
$weak = WeakReference::create($value);
$callback = static function ($object) use ($value) {};
$reflection = new ReflectionClass(CaptureOrdinaryTarget::class);
$ghost = $reflection->newLazyGhost($callback);
unset($value, $callback);
echo json_encode(['lazy-capture', $weak->get() !== null, $reflection->isUninitializedLazyObject($ghost)]), "\n";
$reflection->initializeLazyObject($ghost);
echo json_encode(['retired', $weak->get() === null, $ghost->x]), "\n";
$value = ['before' => 1];
$events = [];
set_error_handler(function ($level, $message) use (&$value, &$events) {
    $events[] = $message;
    $value = ['after' => 2];
    return true;
});
$callback = static function () use ($value, $absent) { return [$value, $absent]; };
restore_error_handler();
echo json_encode(['warning-snapshot', $callback(), $value, $events]), "\n";
$value = new CaptureOrdinaryValue;
$weak = WeakReference::create($value);
set_error_handler(function ($level, $message) { throw new RuntimeException('capture-warning'); });
try {
    $callback = static function () use ($value, $anotherAbsent) {};
} catch (RuntimeException $error) {
    echo json_encode(['throw', $error->getMessage()]), "\n";
}
restore_error_handler();
unset($value);
echo json_encode(['throw-release', $weak->get() === null]), "\n";
