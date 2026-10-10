<?php
function assignmentRhs(&$root, &$events, $mode) {
    $events[] = 'rhs';
    if ($mode === 'throw') throw new Exception('rhs');
    if ($mode === 'replace') $root = ['new' => 3];
    if ($mode === 'scalar') $root = 5;
    return 42;
}
function assignmentKey(&$events, $key) { $events[] = ['key', $key]; return $key; }
function assignmentCase($kind, $mode, $initial) {
    $root = $initial; $events = [];
    set_error_handler(function($level, $message) use (&$events) { $events[] = [$level, $message]; return true; });
    try {
        if ($kind === 'append') $root[] = assignmentRhs($root, $events, $mode);
        if ($kind === 'key') $root[assignmentKey($events, 'x')] = assignmentRhs($root, $events, $mode);
        if ($kind === 'nested') $root[assignmentKey($events, 'x')][assignmentKey($events, 'y')] = assignmentRhs($root, $events, $mode);
        if ($kind === 'nestedAppend') $root[assignmentKey($events, 'x')][] = assignmentRhs($root, $events, $mode);
        if ($kind === 'doubleAppend') $root[][] = assignmentRhs($root, $events, $mode);
        if ($kind === 'appendKey') $root[][assignmentKey($events, 'y')] = assignmentRhs($root, $events, $mode);
    } catch (Throwable $e) { $events[] = [get_class($e), $e->getMessage()]; }
    restore_error_handler();
    return [$kind, $mode, $initial, $root, $events];
}
$rows = [];
foreach (['append', 'key', 'nested', 'nestedAppend', 'doubleAppend', 'appendKey'] as $kind) {
    foreach (['throw', 'replace', 'scalar', 'ok'] as $mode) {
        foreach ([[], ['x' => ['old' => 2]], null, false, true, 5, 'ab'] as $initial)
            $rows[] = assignmentCase($kind, $mode, $initial);
    }
}
foreach ([0, 2, -1, '2', '02', '+2', '2.0', '', null, true, false, 2.5, [], new stdClass] as $key) {
    $root = []; $events = [];
    set_error_handler(function($level, $message) use (&$events) { $events[] = [$level, $message]; return true; });
    try { $value = ($root[assignmentKey($events, $key)] = assignmentRhs($root, $events, 'ok')); }
    catch (Throwable $e) { $value = [get_class($e), $e->getMessage()]; }
    restore_error_handler();
    $rows[] = ['keyType', $key, $root, $value, $events];
}
$a = []; $x = 1; $a[] =& $x; $x = 7;
$rows[] = ['appendReference', $a, $x];
$a = []; $x = 1; $a['x'][] =& $x; $x = 9;
$rows[] = ['nestedReference', $a, $x];
$a = ['x' => 1]; $alias =& $a['x']; $copy = $a; $a['x'] = 3;
$rows[] = ['existingReferenceCow', $a, $copy, $alias];
$a = ['x' => ['old' => 1]]; $copy = $a; $a['x']['new'] = 4;
$rows[] = ['nestedCow', $a, $copy];
$a = [1, 2]; $copy = $a; $returned = ($a[] = $a); $returned[] = 8;
$rows[] = ['selfAppendCow', $a, $copy, $returned];
$a = []; $events = []; $key = 'x'; $a[$key] = ($key = 'y');
$rows[] = ['capturedKey', $a, $key];
$a = []; $b = []; $returned = ($a[] = $b['x'] = 7);
$rows[] = ['chained', $a, $b, $returned];
$a = []; $b = []; $returned = ($a[][] = $b[] = ['v' => 7]);
$rows[] = ['chainedNested', $a, $b, $returned];
class AssignmentMagicBox {
    public $data = [];
    public $events = [];
    public function &__get($name) { $this->events[] = ['get', $name]; return $this->data; }
}
foreach (['throw', 'replace', 'ok'] as $mode) {
    $box = new AssignmentMagicBox;
    try { $box->p[assignmentKey($box->events, 'x')][] = assignmentRhs($box->data, $box->events, $mode); }
    catch (Throwable $e) { $box->events[] = [get_class($e), $e->getMessage()]; }
    $rows[] = ['magic', $mode, $box->data, $box->events];
}
class AssignmentAccess implements ArrayAccess {
    public $data = [];
    public $events = [];
    public function offsetExists($key): bool { $this->events[] = ['exists', $key]; return isset($this->data[$key]); }
    public function &offsetGet($key): mixed { $this->events[] = ['get', $key]; return $this->data[$key]; }
    public function offsetSet($key, $value): void {
        $this->events[] = ['set', $key, $value];
        if ($key === null) $this->data[] = $value; else $this->data[$key] = $value;
    }
    public function offsetUnset($key): void { unset($this->data[$key]); }
}
foreach (['append', 'key', 'nested', 'nestedAppend'] as $kind) {
    foreach (['throw', 'ok'] as $mode) {
        $box = new AssignmentAccess; $events = [];
        try {
            if ($kind === 'append') $box[] = assignmentRhs($box->data, $events, $mode);
            if ($kind === 'key') $box[assignmentKey($events, 'x')] = assignmentRhs($box->data, $events, $mode);
            if ($kind === 'nested') $box[assignmentKey($events, 'x')]['y'] = assignmentRhs($box->data, $events, $mode);
            if ($kind === 'nestedAppend') $box[assignmentKey($events, 'x')][] = assignmentRhs($box->data, $events, $mode);
        } catch (Throwable $e) { $events[] = [get_class($e), $e->getMessage()]; }
        $rows[] = ['access', $kind, $mode, $box->data, $box->events, $events];
    }
}
foreach (['0', '-0', '00', '-01', '01', '+0', '+1', ' 1', '1 ', '1e0', '1.0',
          '9223372036854775807', '9223372036854775808', '-9223372036854775808', '-9223372036854775809',
          '18446744073709551616', '123456789012345678901234567890', '١', '²'] as $key) {
    $a = []; $a[$key] = 4; $copy = $a;
    $rows[] = ['keyGrammar', $key, $a, $a[$key], isset($a[$key])];
    unset($a[$key]);
    $rows[] = ['keyUnset', $key, $a, $copy];
}
$a = [false => 'zero', true => 'one'];
$rows[] = ['booleanRead', $a[false], $a[true], $a[0], $a[1]];
function &assignmentReference(&$a) { return $a['x']; }
$a = []; $ref =& assignmentReference($a); $copy = $a; $ref = 13;
$rows[] = ['returnedReference', $a, $copy, $ref];
class AssignmentValueBox {
    public $data = ['old' => 1];
    public $events = [];
    public function __get($name) { $this->events[] = ['get', $name]; return $this->data; }
}
$box = new AssignmentValueBox; $events = [];
set_error_handler(function($level, $message) use (&$events) { $events[] = [$level, $message]; return true; });
$box->p['x'] = 1;
restore_error_handler();
$rows[] = ['magicValue', $box->data, $box->events, isset($box->p), $events];
class AssignmentValueAccess implements ArrayAccess {
    public $data = ['x' => ['old' => 1]];
    public $events = [];
    public function offsetExists($key): bool { return isset($this->data[$key]); }
    public function offsetGet($key): mixed { $this->events[] = ['get', $key]; return $this->data[$key]; }
    public function offsetSet($key, $value): void { $this->events[] = ['set', $key, $value]; $this->data[$key] = $value; }
    public function offsetUnset($key): void { unset($this->data[$key]); }
}
$box = new AssignmentValueAccess; $events = [];
set_error_handler(function($level, $message) use (&$events) { $events[] = [$level, $message]; return true; });
$box['x']['y'] = 9;
$x = 1;
try { $box['x'] =& $x; } catch (Throwable $e) { $events[] = [get_class($e), $e->getMessage()]; }
restore_error_handler();
$rows[] = ['accessValue', $box->data, $box->events, $events];
function assignmentVariableKeys() {
    $a = []; $key = 'x'; $a[$key] = ($key = 'y');
    return [$a, $key];
}
$rows[] = ['localVariableKey', assignmentVariableKeys()];
function assignmentParenthesized() {
    $a = null; ($a)['x'] = 3;
    return $a;
}
$rows[] = ['parenthesized', assignmentParenthesized()];
function assignmentUndefinedKey($mode) {
    $a = []; $events = [];
    set_error_handler(function($level, $message) use (&$events) { $events[] = [$level, $message]; return true; });
    try { $a[$missing] = assignmentRhs($a, $events, $mode); }
    catch (Throwable $e) { $events[] = [get_class($e), $e->getMessage()]; }
    restore_error_handler();
    return [$mode, $a, $events];
}
$rows[] = ['undefinedKey', assignmentUndefinedKey('throw')];
$rows[] = ['undefinedKey', assignmentUndefinedKey('ok')];
class AssignmentPlainBox { public $p = []; public $q = []; }
$box = new AssignmentPlainBox; $name = 'p'; $box->$name['x'] = ($name = 'q');
$rows[] = ['variableProperty', $box->p, $box->q, $name];
class AssignmentStaticBox { public static $data = []; }
AssignmentStaticBox::$data[] = 4;
$rows[] = ['staticProperty', AssignmentStaticBox::$data];
$a = []; $name = 'a'; $$name[] = 4;
$rows[] = ['variableRoot', $a];
$a = [1]; $alias =& $a[0]; $copy = $a; $a[0] = 3;
$rows[] = ['listReferenceCow', $a, $copy, $alias];
$a = ['02' => 2, 2 => 3, '-0' => 4, '-2' => 5];
$rows[] = ['literalKeys', $a, [...$a, '02' => 9]];
function assignmentLocalProperty() {
    $box = new AssignmentPlainBox; $name = 'p'; $box->$name['x'] = ($name = 'q');
    return [$box->p, $box->q, $name];
}
$rows[] = ['localVariableProperty', assignmentLocalProperty()];
return $rows;
