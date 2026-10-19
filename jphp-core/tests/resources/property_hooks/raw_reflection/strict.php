<?php
declare(strict_types=1);
function check($f) { try { return ['ok', $f()]; } catch (Throwable $e) { return ['throw', get_class($e), $e->getMessage()]; } }
class Hooks {
    public int $x = 2 { get => $this->x * 2; set { $this->x = $value + 1; } }
    public int $v { get => 7; }
    public int $u { get => $this->u; }
}
$h = new Hooks;
$x = new ReflectionProperty(Hooks::class, 'x');
$v = new ReflectionProperty(Hooks::class, 'v');
$u = new ReflectionProperty(Hooks::class, 'u');
$rows = [
    'read' => check(fn() => [$x->getValue($h), $x->getRawValue($h)]),
    'write' => check(function () use ($x, $h) { $x->setRawValue($h, 5); $a = $h->x; $x->setValue($h, 7); return [$a, $x->getRawValue($h)]; }),
    'virtualRead' => check(fn() => $v->getRawValue($h)),
    'virtualWrite' => check(fn() => $v->setRawValue($h, 4)),
    'uninitializedRead' => check(fn() => $u->getRawValue($h)),
    'wrongObjectRead' => check(fn() => $x->getRawValue(new stdClass)),
    'wrongObjectWrite' => check(fn() => $x->setRawValue(new stdClass, 3)),
    'weakRawWrite' => check(function () use ($x, $h) { $x->setRawValue($h, '11'); return $x->getRawValue($h); }),
];
echo json_encode($rows, JSON_UNESCAPED_SLASHES), "\n";
