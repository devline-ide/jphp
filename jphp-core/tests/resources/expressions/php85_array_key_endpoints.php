<?php
function ak_row($id, $value) { echo $id, ':', json_encode($value), "\n"; }
function ak_keys($a) { return [array_key_first($a), array_key_last($a)]; }
$cases = [
    'empty' => [], 'list' => [null, false, 2], 'holes' => [-5 => null, 9 => 2],
    'strings' => ['first' => null, 'last' => false],
    'unicode' => ['ключ' => null, 'қазақ' => 2],
    'numeric-strings' => ['08' => null, '8' => 2, '-2' => 3],
];
foreach ($cases as $id => $a) ak_row($id, ak_keys($a));
$a = ['a' => null, 'b' => false, 'c' => 3];
foreach (['first', 'middle', 'last', 'exhausted', 'before-first'] as $state) {
    reset($a);
    if ($state === 'middle') next($a);
    if ($state === 'last') end($a);
    if ($state === 'exhausted') { end($a); next($a); }
    if ($state === 'before-first') prev($a);
    $before = [key($a), current($a)];
    $keys = [array_key_first($a), array_key_last($a)];
    ak_row('pointer-' . $state, [$keys, $before, [key($a), current($a)]]);
}
$a = ['a' => null, 'b' => 2]; next($a); $b = $a;
unset($b['a']); $b['tail'] = 3;
ak_row('cow', [ak_keys($a), ak_keys($b), key($a), key($b)]);
$alias =& $a; unset($alias['b']); $alias['new'] = null;
ak_row('alias', [ak_keys($a), ak_keys($alias)]); unset($alias);
$v = null; $a = ['start' => &$v, 'end' => &$v]; $v = 7;
ak_row('null-cell', [ak_keys($a), $a]);
foreach (['array_key_first', 'array_key_last'] as $fn) {
    foreach ([null, false, 123, 1.5, 'x', new stdClass()] as $bad) {
        try { $fn($bad); ak_row($fn . '-type', 'unexpected'); }
        catch (Throwable $e) { ak_row($fn . '-type', [get_class($e), $e->getMessage()]); }
    }
    try { $fn(); ak_row($fn . '-arity0', 'unexpected'); }
    catch (Throwable $e) { ak_row($fn . '-arity0', [get_class($e), $e->getMessage()]); }
    try { $fn([], 1); ak_row($fn . '-arity2', 'unexpected'); }
    catch (Throwable $e) { ak_row($fn . '-arity2', [get_class($e), $e->getMessage()]); }
}
$a = ["\xff" => 1, "\xc3\x28" => 2];
ak_row('invalid-byte-keys', [bin2hex(array_key_first($a)), bin2hex(array_key_last($a))]);
$a = ["\0first" => null, "last\0" => false];
ak_row('nul-keys', [bin2hex(array_key_first($a)), bin2hex(array_key_last($a))]);
$a = ["\xff" => 1, 'middle' => 2, "\xfe" => 3]; next($a); $before = key($a);
ak_row('invalid-keys-preserve-pointer', [bin2hex(array_key_first($a)), bin2hex(array_key_last($a)), $before, key($a)]);
foreach (['array_key_first', 'array_key_last'] as $fn) {
    $r = new ReflectionFunction($fn); $t = $r->getReturnType(); $p = $r->getParameters()[0];
    $arms = []; foreach ($t->getTypes() as $arm) $arms[] = [(string)$arm, $arm->allowsNull()];
    ak_row('reflection-' . $fn, [get_class($t), (string)$t, $t->allowsNull(), $arms, $p->getName(), (string)$p->getType(), $p->isPassedByReference(), $p->isVariadic(), $r->getNumberOfRequiredParameters(), $r->getNumberOfParameters()]);
}
foreach (['sort', 'strpos', 'hex2bin'] as $fn) {
    $t = (new ReflectionFunction($fn))->getReturnType();
    ak_row('reflection-existing-' . $fn, [get_class($t), (string)$t, $t->allowsNull()]);
}
