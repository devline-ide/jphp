<?php
function numeric_row(&$rows, $label, $value) { $rows[] = [$label, $value]; }
function numeric_float_arg(float $value): float { return $value; }
function numeric_float_return($value): float { return $value; }
$rows = [];
$strings = ['', ' ', '+', '-', '.', '0', '-0', '+0', '00', '08', '42', '+42', '-42',
    '42foo', '42 foo', 'foo42', ' 42', "42\t\r\n", "\v\f42", chr(0).'42', '42'.chr(0),
    chr(1).'42', '42'.chr(1), ' 42', '42 ', '١٢', '.5', '-.5', '1.', '1.e2', '1.9e3foo',
    '1e+', '1e-', '1e', '1.e+', '.e2', '1..2', '1e2e3', '1e-2x', '1_000', '0xFF', '0b11', '0o77',
    'INF', '-INF', 'NAN', 'Infinity', '1e400', '-1e400', '1e-400', '-1e-400', '-0.0',
    '9223372036854775807', '9223372036854775808', '-9223372036854775808', '-9223372036854775809',
    '9223372036854775807.0', '18446744073709551616', '9.223372036854775e18',
    '00000000000000000000000000000000000000000001', '99999999999999999999999999999999999999999999'];
$numeric = 'is_numeric'; $integer = 'intval';
foreach ($strings as $i => $value) {
    numeric_row($rows, "string:$i:numeric", is_numeric($value));
    numeric_row($rows, "string:$i:dynamic", $numeric($value));
    numeric_row($rows, "string:$i:int-cast", (int)$value);
    numeric_row($rows, "string:$i:intval", intval($value));
    numeric_row($rows, "string:$i:dynamic-int", $integer($value));
    numeric_row($rows, "string:$i:float-cast", (float)$value);
    numeric_row($rows, "string:$i:floatval", floatval($value));
    numeric_row($rows, "string:$i:doubleval", doubleval($value));
    try { $argument = numeric_float_arg($value); }
    catch (TypeError $error) { $argument = get_class($error); }
    try { $returned = numeric_float_return($value); }
    catch (TypeError $error) { $returned = get_class($error); }
    numeric_row($rows, "string:$i:float-argument", $argument);
    numeric_row($rows, "string:$i:float-return", $returned);
}
$warnings = [];
set_error_handler(function($errno, $message) use (&$warnings) { $warnings[] = $errno; return true; });
class NumericStringable {
    public static $calls = 0;
    public function __toString() { ++self::$calls; return '123'; }
}
$values = [null, false, true, 0, 7, 0.0, -0.0, 1.5, INF, -INF, NAN, 1e20, -1e20,
    9223372036854775808.0, -9223372036854775808.0, 18446744073709551616.0,
    [], [1], new stdClass, new NumericStringable];
foreach ($values as $i => $value) {
    numeric_row($rows, "value:$i:numeric", is_numeric($value));
    $warnings = [];
    numeric_row($rows, "value:$i:int-cast", (int)$value);
    numeric_row($rows, "value:$i:int-warnings", $warnings);
    $warnings = [];
    numeric_row($rows, "value:$i:intval", intval($value, 16));
    numeric_row($rows, "value:$i:intval-warnings", $warnings);
    $warnings = [];
    numeric_row($rows, "value:$i:float-cast", (float)$value);
    numeric_row($rows, "value:$i:float-warnings", $warnings);
    $warnings = [];
    numeric_row($rows, "value:$i:floatval", floatval($value));
    numeric_row($rows, "value:$i:floatval-warnings", $warnings);
}
numeric_row($rows, 'object-string-calls', NumericStringable::$calls);
$baseValues = ['0x10', '0XFFtail', '0b10', '0B11', '0o10', '010', '08', '  -0x10tail',
    '1e3', '1.5e3', '9223372036854775808', '-9223372036854775809',
    'zzzzzzzzzzzzzzzzzzzzzzzz', '+7fffffff', '-8000000000000000', '0x', '0b', '', '+', '  -', chr(0).'12'];
foreach ($baseValues as $i => $value) {
    foreach ([0, 2, 8, 10, 16, 36, 1, 37, -1] as $base) {
        numeric_row($rows, "base:$i:$base", intval($value, $base));
    }
}
foreach (['int', 'integer', 'INTEGER', 'float', 'double', 'bool', 'boolean', 'string', 'null', 'array', 'object'] as $type) {
    $value = '1.9e3foo'; $alias =& $value;
    numeric_row($rows, "settype:$type:ok", settype($value, $type));
    numeric_row($rows, "settype:$type:value", $alias);
    unset($alias);
}
foreach (['real', '', ' int'] as $type) {
    $value = 42;
    try { settype($value, $type); $error = 'missed'; }
    catch (ValueError $caught) { $error = get_class($caught); }
    numeric_row($rows, "invalid:$type:error", $error);
    numeric_row($rows, "invalid:$type:unchanged", $value);
}
$warnings = [];
if (false) { $unused = (int)1e20; }
numeric_row($rows, 'unexecuted-cast-warnings', $warnings);
numeric_row($rows, 'folded-int-string', (int)'1.9e3foo');
numeric_row($rows, 'folded-float-string', (float)'1e+');
numeric_row($rows, 'literal-overflow-cast', (int)1e20);
numeric_row($rows, 'literal-overflow-warnings', $warnings);
numeric_row($rows, 'literal-numeric-tail', is_numeric('42foo'));
numeric_row($rows, 'literal-numeric-bool', is_numeric(true));
numeric_row($rows, 'callback-intval', call_user_func('intval', '1e3'));
numeric_row($rows, 'reflection-intval', (new ReflectionFunction('intval'))->invoke('0xFF', 0));
restore_error_handler();
return $rows;
