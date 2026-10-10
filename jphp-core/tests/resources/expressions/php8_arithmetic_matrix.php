<?php
function arithmetic_op($a, $b, $op) {
    switch ($op) {
        case '+': return $a + $b;
        case '-': return $a - $b;
        case '*': return $a * $b;
        case '/': return $a / $b;
        case '%': return $a % $b;
        case '**': return $a ** $b;
        case 'u+': return +$a;
        case 'u-': return -$a;
    }
}
class ArithmeticStringable {
    public static $calls = 0;
    public function __toString() { ++self::$calls; return '123'; }
}
$values = [null, false, true, 0, 1, -1, 2, 7, PHP_INT_MAX, ~PHP_INT_MAX,
    0.0, -0.0, 1.5, -1.5, INF, -INF, NAN, 1e20, -1e20,
    '2', '-0', '-0.0', '.5', '1e+', '1e2tail', 'abc', '0xFF', '', [], [1],
    ['x' => [2]], new stdClass, new ArithmeticStringable, fopen('php://memory', 'r+')];
$rows = []; $warnings = [];
set_error_handler(function($errno, $message) use (&$warnings) { $warnings[] = $errno; return true; });
foreach ($values as $i => $a) {
    foreach ($values as $j => $b) {
        foreach (['+', '-', '*', '/', '%', '**'] as $op) {
            $warnings = []; $error = '';
            try { $result = arithmetic_op($a, $b, $op); }
            catch (Throwable $caught) { $result = null; $error = get_class($caught) . ':' . $caught->getMessage(); }
            $rows[] = ["$i:$j:$op", $result, $error, $warnings];
        }
    }
    foreach (['u+', 'u-'] as $op) {
        $warnings = []; $error = '';
        try { $result = arithmetic_op($a, null, $op); }
        catch (Throwable $caught) { $result = null; $error = get_class($caught) . ':' . $caught->getMessage(); }
        $rows[] = ["$i:$op", $result, $error, $warnings];
    }
}
$rows[] = ['object-string-calls', ArithmeticStringable::$calls, '', []];
fclose($values[33]);
restore_error_handler();
return $rows;
