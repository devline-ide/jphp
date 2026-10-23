<?php
declare(strict_types=0);
require __DIR__ . '/coercion-declarations-weak.php';
require __DIR__ . '/coercion-declarations-strict.php';
$depCoerceEvents = [];
set_error_handler(function($severity, $message) use (&$depCoerceEvents) { $depCoerceEvents[] = [$severity, $message]; return true; });
foreach (['dep_coerce_weak', 'dep_coerce_strict'] as $name) {
    $depCoerceEvents = [];
    try { $value = ['ok', $name()]; }
    catch (Throwable $error) { $value = ['throw', get_class($error), $error->getMessage()]; }
    echo $name, ':', json_encode([$value, $depCoerceEvents]), "\n";
    try { $a = (new ReflectionFunction($name))->getAttributes(Deprecated::class)[0]->newInstance(); $value = ['ok', $a->message, $a->since]; }
    catch (Throwable $error) { $value = ['throw', get_class($error), $error->getMessage()]; }
    echo $name, '-reflection:', json_encode($value), "\n";
}
restore_error_handler();
