<?php
$rows = []; $warnings = [];
function arithmetic_row(&$rows, $label, $value) { $rows[] = [$label, $value, '', []]; }
set_error_handler(function($errno, $message) use (&$warnings) { $warnings[] = $errno; return true; });
arithmetic_row($rows, 'pow-right-assoc', 2 ** 3 ** 2);
arithmetic_row($rows, 'pow-parentheses', (2 ** 3) ** 2);
arithmetic_row($rows, 'unary-minus-pow', -2 ** 2);
arithmetic_row($rows, 'unary-plus-pow', +2 ** 3);
arithmetic_row($rows, 'negative-base', (-2) ** 3);
arithmetic_row($rows, 'negative-exponent', 2 ** -3);
arithmetic_row($rows, 'multiple-unary', -+-2 ** 2);
arithmetic_row($rows, 'cast-before-power', (int) 2 ** .5);
arithmetic_row($rows, 'cast-exponent', 4 ** (int) .5);
arithmetic_row($rows, 'bitnot-before-power', ~2 ** 3);
arithmetic_row($rows, 'boolean-not-before-power', !0 ** 0);
arithmetic_row($rows, 'boolean-not-before-multiply', !0 * 0);
arithmetic_row($rows, 'power-before-multiply', 2 * 3 ** 2);
arithmetic_row($rows, 'multiply-after-power', 2 ** 3 * 2);
arithmetic_row($rows, 'int-power-boundary', (-2) ** 63);
arithmetic_row($rows, 'float-power-overflow', 3 ** 40);
arithmetic_row($rows, 'float-float-power', 2.5 ** 1.5);
arithmetic_row($rows, 'div-overflow', (~PHP_INT_MAX) / -1);
arithmetic_row($rows, 'div-integral', '12' / '3');
arithmetic_row($rows, 'div-real', 12.0 / 3);
$warnings = [];
if (false) { $unexecuted = 'abc' + 1; $unexecuted = 1 / 0; $unexecuted = 0 ** -1; }
arithmetic_row($rows, 'dead-code-warnings', $warnings);
foreach (['+', '-', '*', '/', '%', '**'] as $op) {
    $a = 7; $alias =& $a; $warnings = []; $error = '';
    try {
        switch ($op) {
            case '+': $result = ($a += '1e+'); break;
            case '-': $result = ($a -= '1e+'); break;
            case '*': $result = ($a *= '1e+'); break;
            case '/': $result = ($a /= '1e+'); break;
            case '%': $result = ($a %= '1e+'); break;
            case '**': $result = ($a **= '1e+'); break;
        }
    } catch (Throwable $caught) { $result = null; $error = get_class($caught) . ':' . $caught->getMessage(); }
    $rows[] = ["compound:$op:result", $result, $error, $warnings];
    arithmetic_row($rows, "compound:$op:alias", $alias);
    unset($alias);
}
$a = 1; $result = ($a += ($a = 10));
arithmetic_row($rows, 'rhs-changes-local', [$a, $result]);
$a = [1]; $a[0] += ($a[0] = 10);
arithmetic_row($rows, 'rhs-changes-index', $a);
$a = [1]; $a[0] += ($a = [9])[0];
arithmetic_row($rows, 'rhs-replaces-array', $a);
$a = ['x' => 7]; $copy = $a; $result = ($a['x'] *= '2');
arithmetic_row($rows, 'array-compound-cow', [$a, $copy, $result]);
$a = 7; $warnings = []; try { $a /= 0; } catch (DivisionByZeroError $e) {}
arithmetic_row($rows, 'division-error-preserves-target', $a);
$a = 7; try { $a += 'abc'; } catch (TypeError $e) {}
arithmetic_row($rows, 'type-error-preserves-target', $a);
class ArithmeticProperties { public $value = 7; public static $staticValue = 7; }
$object = new ArithmeticProperties; $object->value *= '2'; ArithmeticProperties::$staticValue += '2';
arithmetic_row($rows, 'property-compound', [$object->value, ArithmeticProperties::$staticValue]);
class ArithmeticMagic {
    public $events = [];
    public function __get($field) { $this->events[] = "get:$field"; return 7; }
    public function __set($field, $value) { $this->events[] = "set:$field:$value"; }
}
function arithmetic_rhs($object) { $object->events[] = 'rhs'; return '2'; }
$magic = new ArithmeticMagic; $value = ($magic->value *= arithmetic_rhs($magic));
arithmetic_row($rows, 'magic-compound', [$value, $magic->events]);
$shared = 1; $left = ['a' => [1]]; $right = ['b' => &$shared]; $union = $left + $right;
$union['a'][0] = 2; $shared = 3;
arithmetic_row($rows, 'union-cow-and-reference', [$left, $right, $union]);
foreach ([null, false, true, 1, [], 'abc', new stdClass] as $index => $initial) {
    $a = $initial; $warnings = []; $error = ''; $result = null;
    try { $result = ($a[0] += 2); } catch (Throwable $caught) { $error = get_class($caught) . ':' . $caught->getMessage(); }
    $rows[] = ["offset-root:$index", [$a, $result], $error, $warnings];
}
foreach ([[], new stdClass, 1.5, INF, NAN] as $index => $key) {
    $a = [7]; $warnings = []; $error = ''; $result = null;
    try { $result = ($a[$key] += 2); } catch (Throwable $caught) { $error = get_class($caught) . ':' . $caught->getMessage(); }
    $rows[] = ["offset-key:$index", [$a, $result], $error, $warnings];
}
$a = []; $warnings = []; $result = ($a[0][1] += 2);
$rows[] = ['offset-nested-missing', [$a, $result], '', $warnings];
class ArithmeticArrayAccess implements ArrayAccess {
    public $events = []; public $value = 7;
    public function offsetExists($key) { $this->events[] = 'exists'; return true; }
    public function offsetGet($key) { $this->events[] = 'get'; return $this->value; }
    public function offsetSet($key, $value) { $this->events[] = "set:$value"; $this->value = $value; }
    public function offsetUnset($key) {}
}
function arithmetic_key($a) { $a->events[] = 'key'; return 0; }
function arithmetic_access_rhs($a) { $a->events[] = 'rhs'; $a->value = 10; return 2; }
$a = new ArithmeticArrayAccess; $result = ($a[arithmetic_key($a)] += arithmetic_access_rhs($a));
arithmetic_row($rows, 'arrayaccess-evaluation-order', [$a->events, $a->value, $result]);
function arithmetic_local() {
    $a = 1; $result = ($a += ($a = 10));
    $b = 7; $product = ($b *= 2.5);
    return [$a, $result, $b, $product, 2.5 + $a, $a + 2.5, 2.5 - $a, $a - 2.5];
}
arithmetic_row($rows, 'optimized-locals-and-category2', arithmetic_local());
function arithmetic_local_failure() {
    $a = 7; try { $a /= 0; } catch (DivisionByZeroError $e) {}
    $b = 7; try { $b += 'abc'; } catch (TypeError $e) {}
    return [$a, $b];
}
arithmetic_row($rows, 'optimized-locals-preserve-on-error', arithmetic_local_failure());
function arithmetic_primitives($letter, $other, $number, $second) {
    return [ord($letter) + ord($other), ord($letter) - ord($other), ord($letter) * ord($other),
        sqrt($number) + sqrt($second), sqrt($number) - sqrt($second), sqrt($number) * sqrt($second),
        ord($letter) + sqrt($number), sqrt($number) + ord($letter),
        ord($letter) - sqrt($number), sqrt($number) - ord($letter),
        ord($letter) * sqrt($number), sqrt($number) * ord($letter),
        2 + sqrt($number), sqrt($number) + 2, 2 - sqrt($number), sqrt($number) - 2,
        2 * sqrt($number), sqrt($number) * 2, 2 + ord($letter), ord($letter) - 2];
}
arithmetic_row($rows, 'primitive-stack-order', arithmetic_primitives('A', 'B', 4, 9));
function arithmetic_cast_primitives($a, $b) {
    return [(int)$a + (int)$b, (int)$a - (int)$b, (int)$a * (int)$b,
        (float)$a + (float)$b, (float)$a - (float)$b, (float)$a * (float)$b,
        (int)$a + (float)$b, (float)$b + (int)$a,
        (int)$a - (float)$b, (float)$b - (int)$a,
        (int)$a * (float)$b, (float)$b * (int)$a,
        2 + (float)$b, (float)$b + 2, 2 - (float)$b, (float)$b - 2,
        2 * (float)$b, (float)$b * 2, 2 + (int)$a, (int)$a - 2];
}
arithmetic_row($rows, 'cast-primitive-stack-order', arithmetic_cast_primitives('7', '2.5'));
restore_error_handler();
return $rows;
