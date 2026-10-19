<?php
declare(strict_types=1);
require_once __DIR__ . '/donor.php';
require_once __DIR__ . '/consumer.php';
$o = new Host;
try { echo "get:" . $o->p . "\n"; }
catch (Throwable $e) { echo get_class($e) . "|" . $e->getMessage() . "\n"; }
try { $o->p = "9"; echo "set:ok\n"; }
catch (Throwable $e) { echo get_class($e) . "|" . $e->getMessage() . "\n"; }
$ref =& $o->q; $ref = 8;
echo "stored:" . json_encode(get_mangled_object_vars($o)) . "\n";
$hook = (new ReflectionProperty(Host::class, 'p'))->getHook(PropertyHookType::Get);
echo "scope:" . $hook->getDeclaringClass()->getName() . "\n";
