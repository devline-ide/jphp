<?php
class OrdinaryReceiver {
    const TAG = 'receiver';
    public function identity() { return $this; }
    public static function missing() { return $this; }
    public function lexical() { return function () { return [$this, self::TAG]; }; }
}
class OtherReceiver { const TAG = 'other'; }
$a = new OrdinaryReceiver();
echo 'instance=', (int)($a->identity() === $a), "\n";
try { OrdinaryReceiver::missing(); echo "static=missing-error\n"; }
catch (Error $e) { echo 'static=', get_class($e), ':', $e->getMessage(), "\n"; }
$closure = $a->lexical(); $first = $closure(); $b = new OtherReceiver();
$bound = $closure->bindTo($b, OtherReceiver::class); $second = $bound();
echo 'closure=', (int)($first[0] === $a), (int)($first[1] === 'receiver'),
    (int)($second[0] === $b), (int)($second[1] === 'other'), "\n";
