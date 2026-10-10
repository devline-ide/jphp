<?php
$out = '';
function recordCoalesce($name, $value) { global $out; $out .= $name . ':' . serialize($value) . "\n"; }
$events = [];
function coalesceEvent($name, $value) { global $events; $events[] = $name; return $value; }
function coalesceThrow() { throw new Exception('rhs'); }
foreach ([null, false, 0, 0.0, '', [], 'ready'] as $initial) {
    $events = [];
    $a = $initial;
    $result = ($a ??= coalesceEvent('rhs', 7));
    recordCoalesce('values', [$a, $result, $events]);
}
$events = [];
$result = ($missing ??= coalesceEvent('rhs', 11));
recordCoalesce('missing', [$missing, $result, $events]);
$a = null; $b =& $a; $result = ($a ??= 3); $b = 4;
recordCoalesce('reference', [$a, $b, $result]);
$a = []; $copy = $a; $events = [];
$result = ($a[coalesceEvent('key1', 'x')][coalesceEvent('key2', 'y')] ??= coalesceEvent('rhs', count($a)));
recordCoalesce('nested', [$a, $copy, $result, $events]);
$copy = $a; $events = [];
$result = ($a[coalesceEvent('key1', 'x')][coalesceEvent('key2', 'y')] ??= coalesceEvent('rhs', 99));
$a['x']['y'] = 8;
recordCoalesce('nested-existing-cow', [$a, $copy, $result, $events]);
$a = []; $events = [];
try { $a[coalesceEvent('key', 'x')] ??= coalesceThrow(); } catch (Exception $e) { $events[] = $e->getMessage(); }
recordCoalesce('throw-no-write', [$a, $events]);
$a = []; $result = ($a['x'] ??= ($a = ['z' => 2]));
recordCoalesce('rhs-changes-root', [$a, $result]);
$a = null; $b = null; $result = ($a ??= $b ??= 12);
recordCoalesce('chain', [$a, $b, $result]);
$a = null; $result = ($assigned = $a ??= 1 + 2 * 3);
recordCoalesce('assignment-priority', [$a, $assigned, $result]);
$a = null; $result = ($a ??= 1 and false);
recordCoalesce('and-priority', [$a, $result]);
$a = null; $result = ($a ??= 0 or true);
recordCoalesce('or-priority', [$a, $result]);
$a = null; $result = (true and $a ??= 9);
recordCoalesce('and-left', [$a, $result]);
$a = null; $result = ($a ??= (0 or 5));
recordCoalesce('parenthesized-rhs', [$a, $result]);
$a = null; $result = ($a ??= false || true and false);
recordCoalesce('mixed-logic-priority', [$a, $result]);
$a = 1; $b = null; $result = $a + $b ??= 2;
recordCoalesce('assignment-under-addition', [$a, $b, $result]);
$a = null; $result = -$a ??= 2;
recordCoalesce('assignment-under-negation', [$a, $result]);
$a = null; $result = !$a ??= 2;
recordCoalesce('assignment-under-not', [$a, $result]);
$a = null; $result = (int)$a ??= 2;
recordCoalesce('assignment-under-cast', [$a, $result]);
$name = 'indirect'; $$name ??= 13;
recordCoalesce('variable-variable', [$indirect]);
class CoalesceBox {
    public $value = null;
    public $array = [];
    private $hidden = null;
    static $shared = null;
    function hidden() { return $this->hidden ??= 14; }
    static function own() { return self::$shared ??= 15; }
}
$box = new CoalesceBox; $events = [];
$result = ($box->{coalesceEvent('property', 'value')} ??= coalesceEvent('rhs', 16));
recordCoalesce('property', [$box->value, $result, $events, $box->hidden()]);
$events = [];
$result = ($box->value ??= coalesceEvent('rhs', 99));
recordCoalesce('property-existing', [$box->value, $result, $events]);
function coalesceBox($box) { return coalesceEvent('box', $box); }
$box->value = null; $events = [];
$result = (coalesceBox($box)->{coalesceEvent('property', 'value')} ??= coalesceEvent('rhs', 21));
recordCoalesce('call-base', [$box->value, $result, $events]);
$result = ($box->array['x'] ??= count($box->array));
recordCoalesce('property-array', [$box->array, $result]);
recordCoalesce('static', [CoalesceBox::own(), CoalesceBox::$shared ??= 99, CoalesceBox::$shared]);
$class = 'CoalesceBox'; $field = 'shared';
recordCoalesce('dynamic-static', [$class::${$field} ??= 100, CoalesceBox::$shared]);
class CoalesceMagic {
    public $data = [];
    public $exists = false;
    function __isset($name) { coalesceEvent('isset:' . $name, 0); return $this->exists; }
    function __get($name) { return coalesceEvent('get:' . $name, $this->data[$name] ?? null); }
    function __set($name, $value) { coalesceEvent('set:' . $name, 0); $this->data[$name] = $value; }
}
foreach ([false, true] as $exists) {
    $magic = new CoalesceMagic; $magic->exists = $exists; $events = [];
    $result = ($magic->item ??= coalesceEvent('rhs', 17));
    recordCoalesce('magic-missing', [$magic->data, $result, $events]);
}
$magic = new CoalesceMagic; $magic->exists = true; $magic->data['item'] = 0; $events = [];
$result = ($magic->item ??= coalesceEvent('rhs', 99));
recordCoalesce('magic-existing', [$magic->data, $result, $events]);
$magic->data['item'] = []; $events = [];
$result = ($magic->item[coalesceEvent('key', 'x')] ??= coalesceEvent('rhs', 22));
recordCoalesce('magic-nested', [$magic->data, $result, $events]);
class CoalesceOffsets implements ArrayAccess {
    public $data = [];
    function offsetExists($key) { coalesceEvent('exists:' . $key, 0); return array_key_exists($key, $this->data); }
    function offsetGet($key) { return coalesceEvent('get:' . $key, $this->data[$key]); }
    function offsetSet($key, $value) { coalesceEvent('set:' . $key, 0); $this->data[$key] = $value; }
    function offsetUnset($key) { unset($this->data[$key]); }
}
foreach ([[], ['item' => null], ['item' => 0]] as $data) {
    $access = new CoalesceOffsets; $access->data = $data; $events = [];
    $result = ($access[coalesceEvent('key', 'item')] ??= coalesceEvent('rhs', 18));
    recordCoalesce('offsets', [$access->data, $result, $events]);
}
$access->data = ['item' => []]; $events = [];
$result = ($access['item'][coalesceEvent('key', 'x')] ??= coalesceEvent('rhs', 23));
recordCoalesce('offsets-nested', [$access->data, $result, $events]);
$a = 'abc'; $events = []; $result = ($a[1] ??= coalesceEvent('rhs', 'z'));
recordCoalesce('string-existing', [$a, $result, $events]);
$a = 'a'; $copy = $a; $result = ($a[3] ??= 12);
recordCoalesce('string-missing', [$a, $copy, $result]);
$a = 'abc'; $result = ($a[-1] ??= 'z');
recordCoalesce('string-negative', [$a, $result]);
$a = 'abc'; $result = ($a[-9] ??= 'z');
recordCoalesce('string-negative-outside', [$a, $result]);
try { $a['foo'] ??= 'z'; } catch (TypeError $e) { recordCoalesce('string-bad-key', get_class($e)); }
foreach ([null, false, 3, true] as $base) {
    $a = $base; $events = [];
    try { $result = ($a['x'] ??= coalesceEvent('rhs', 24)); }
    catch (Error $e) { $result = get_class($e); }
    recordCoalesce('scalar-container', [$a, $result, $events]);
}
$a = null; $events = [];
try { $a->x ??= coalesceEvent('rhs', 25); } catch (Error $e) { $result = get_class($e); }
recordCoalesce('null-property-error', [$a, $result, $events]);
$a = []; $events = []; $result = 'missed';
try { $a[[]] ??= coalesceEvent('rhs', 26); } catch (Error $e) { $result = get_class($e); }
recordCoalesce('invalid-array-key', [$a, $result, $events]);
$a = null; $events = []; $result = 'missed';
try { $a[[]] ??= coalesceEvent('rhs', 27); } catch (Error $e) { $result = get_class($e); }
recordCoalesce('invalid-key-null-root', [$a, $result, $events]);
$events = [];
$closure = function() { $a = null; return $a ??= coalesceEvent('closure', 28); };
recordCoalesce('closure', [$closure(), $events]);
function coalesceGenerator() { $a = null; yield ($a ??= 29); yield ($a ??= 30); }
$values = []; foreach (coalesceGenerator() as $value) { $values[] = $value; }
recordCoalesce('generator', $values);
class CoalesceChild extends CoalesceBox { static function late() { return static::$shared ??= 31; } }
CoalesceBox::$shared = null;
recordCoalesce('late-static', [CoalesceChild::late(), CoalesceBox::$shared]);
$a = null; $result = ($a ??= true ? 32 : 33);
recordCoalesce('ternary-rhs', [$a, $result]);
$a = null; $result = (true ? $a ??= 34 : 35);
recordCoalesce('ternary-value', [$a, $result]);
function coalesceYield() { $a = []; $a['x'] ??= yield 36; yield $a['x']; }
$generator = coalesceYield(); $first = $generator->current(); $second = $generator->send(37);
recordCoalesce('yield-rhs', [$first, $second]);
return $out;
