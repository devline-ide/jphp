<?php
class RefArgOwner {
    public ?int $nullable;
    public int $required;
    public ?array $array;
    public ?string $text;
    public RefArgOwner $child;
    public ?RefArgOwner $nullableChild;
    public mixed $anything;
    public static RefArgOwner $staticChild;
    public static ?int $staticNullable;
    public static int $staticRequired;
}
function refarg_write(&$value) { $value = 7; return $value; }
function refarg_read($value) { return $value; }
function refarg_bad(&$value) { $value = 'bad'; }
function refarg_pair($first, &$second) { $second = $first; return $second; }
function refarg_variadic(&...$values) { $values[0] = 8; return $values[0]; }
function refarg_case($label, $callback) {
    try { return [$label, 'ok', $callback()]; }
    catch (Throwable $e) { return [$label, get_class($e), $e->getMessage()]; }
}
class RefArgMethods {
    public function write(&$value) { return refarg_write($value); }
    public static function staticWrite(&$value) { return refarg_write($value); }
}
class RefArgCallable {
    public function __invoke(&$value) { return refarg_write($value); }
}
class RefArgConstructor {
    public function __construct(&$value) { $value = 9; }
}
class RefArgMagic {
    public function __get($property) { return [9]; }
}
function refarg_replace_array($o) { $o->array = [2]; return false; }
function refarg_mutate_array($o) { $o->array[0] = 2; return false; }
function refarg_mutate_scalar(&$value) { $value = 2; return false; }
$rows = [];
$rows[] = refarg_case('direct-nullable', function () { $o = new RefArgOwner; refarg_write($o->nullable); return $o->nullable; });
$rows[] = refarg_case('direct-value', function () { $o = new RefArgOwner; return refarg_read($o->nullable); });
$rows[] = refarg_case('direct-required', function () { $o = new RefArgOwner; return refarg_write($o->required); });
$rows[] = refarg_case('dynamic-reference', function () { $o = new RefArgOwner; $f = 'refarg_write'; $f($o->nullable); return $o->nullable; });
$rows[] = refarg_case('dynamic-value', function () { $o = new RefArgOwner; $f = 'refarg_read'; return $f($o->nullable); });
$rows[] = refarg_case('closure-reference', function () { $o = new RefArgOwner; $f = function (&$v) { $v = 6; }; $f($o->nullable); return $o->nullable; });
$rows[] = refarg_case('invokable-reference', function () { $o = new RefArgOwner; $f = new RefArgCallable; $f($o->nullable); return $o->nullable; });
$rows[] = refarg_case('method-reference', function () { $o = new RefArgOwner; $m = new RefArgMethods; $m->write($o->nullable); return $o->nullable; });
$rows[] = refarg_case('dynamic-method-reference', function () { $o = new RefArgOwner; $m = new RefArgMethods; $name = 'write'; $m->$name($o->nullable); return $o->nullable; });
$rows[] = refarg_case('static-method-reference', function () { $o = new RefArgOwner; RefArgMethods::staticWrite($o->nullable); return $o->nullable; });
$rows[] = refarg_case('array-callable-reference', function () { $o = new RefArgOwner; $f = [new RefArgMethods, 'write']; $f($o->nullable); return $o->nullable; });
$rows[] = refarg_case('constructor-reference', function () { $o = new RefArgOwner; new RefArgConstructor($o->nullable); return $o->nullable; });
$rows[] = refarg_case('guard-kept', function () { $o = new RefArgOwner; refarg_write($o->nullable); try { refarg_bad($o->nullable); } catch (TypeError $e) { return [$e->getMessage(), $o->nullable]; } return 'missing-TypeError'; });
$rows[] = refarg_case('array-element-value-read', function () { $o = new RefArgOwner; return refarg_read($o->array[0]); });
$rows[] = refarg_case('arithmetic-read', function () { $o = new RefArgOwner; return refarg_write($o->nullable + 0); });
$rows[] = refarg_case('nested-value-call', function () { $o = new RefArgOwner; return refarg_write(refarg_read($o->nullable)); });
$rows[] = refarg_case('intermediate-property-read', function () { $o = new RefArgOwner; return refarg_write($o->child->nullable); });
$rows[] = refarg_case('nested-chain-reference', function () { $o = new RefArgOwner; $o->child = new RefArgOwner; refarg_write($o->child->nullable); return $o->child->nullable; });
$rows[] = refarg_case('field-once', function () { $o = new RefArgOwner; $n = 0; $key = function () use (&$n) { ++$n; return 'nullable'; }; refarg_write($o->{$key()}); return [$n, $o->nullable]; });
$rows[] = refarg_case('receiver-once', function () { $o = new RefArgOwner; $n = 0; $get = function () use ($o, &$n) { ++$n; return $o; }; refarg_write($get()->nullable); return [$n, $o->nullable]; });
$rows[] = refarg_case('key-inner-value-read', function () { $o = new RefArgOwner; $p = new RefArgOwner; return refarg_write($o->{refarg_read($p->text)}); });
$rows[] = refarg_case('missing-callee-order', function () { $o = new RefArgOwner; $n = 0; $key = function () use (&$n) { ++$n; return 'nullable'; }; $f = 'missing_refarg_function'; try { $f($o->{$key()}); } catch (Error $e) { return [$e->getMessage(), $n]; } return 'missing-Error'; });
$rows[] = refarg_case('static-value', function () { return refarg_read(RefArgOwner::$staticNullable); });
$rows[] = refarg_case('static-reference', function () { refarg_write(RefArgOwner::$staticNullable); return RefArgOwner::$staticNullable; });
$rows[] = refarg_case('static-required', function () { return refarg_write(RefArgOwner::$staticRequired); });
$rows[] = refarg_case('dynamic-static-reference', function () { RefArgOwner::$staticNullable = null; $class = 'RefArgOwner'; $field = 'staticNullable'; $f = 'refarg_write'; $f($class::$$field); return RefArgOwner::$staticNullable; });
$rows[] = refarg_case('second-parameter', function () { $o = new RefArgOwner; refarg_pair(4, $o->nullable); return $o->nullable; });
$rows[] = refarg_case('variadic-parameter', function () { $o = new RefArgOwner; refarg_variadic($o->nullable); return $o->nullable; });
$rows[] = refarg_case('native-count-reference', function () { $o = new RefArgOwner; $s = str_replace('a', 'b', 'aa', $o->nullable); return [$s, $o->nullable]; });
$rows[] = refarg_case('dynamic-native-count-reference', function () { $o = new RefArgOwner; $f = 'str_replace'; $s = $f('a', 'b', 'aa', $o->nullable); return [$s, $o->nullable]; });
$rows[] = refarg_case('native-varargs-reference', function () { $o = new RefArgOwner; $n = sscanf('4', '%d', $o->nullable); return [$n, $o->nullable]; });
$rows[] = refarg_case('native-readonly-is_array', function () { $o = new RefArgOwner; return is_array($o->nullable); });
$rows[] = refarg_case('dynamic-native-readonly-is_array', function () { $o = new RefArgOwner; $f = 'is_array'; return $f($o->nullable); });
$rows[] = refarg_case('native-readonly-in_array', function () { $o = new RefArgOwner; return in_array(1, $o->array); });
$rows[] = refarg_case('native-readonly-current', function () { $o = new RefArgOwner; return current($o->array); });
$rows[] = refarg_case('native-readonly-key', function () { $o = new RefArgOwner; return key($o->array); });
$rows[] = refarg_case('native-readonly-is_callable', function () { $o = new RefArgOwner; return is_callable($o->nullable); });
$rows[] = refarg_case('native-readonly-set_error_handler', function () { $o = new RefArgOwner; return set_error_handler($o->nullable); });
$rows[] = refarg_case('nested-ref-child-state', function () { $o = new RefArgOwner; try { refarg_write($o->child->nullable); } catch (Error $e) { return [$e->getMessage(), (new ReflectionProperty(RefArgOwner::class, 'child'))->isInitialized($o)]; } return 'missing-Error'; });
$rows[] = refarg_case('nested-ref-nullable-child-state', function () { $o = new RefArgOwner; try { refarg_write($o->nullableChild->nullable); } catch (Error $e) { return [$e->getMessage(), (new ReflectionProperty(RefArgOwner::class, 'nullableChild'))->isInitialized($o)]; } return 'missing-Error'; });
$rows[] = refarg_case('nested-ref-static-child-state', function () { try { refarg_write(RefArgOwner::$staticChild->nullable); } catch (Error $e) { return [$e->getMessage(), (new ReflectionProperty(RefArgOwner::class, 'staticChild'))->isInitialized()]; } return 'missing-Error'; });
$rows[] = refarg_case('nested-value-child-state', function () { $o = new RefArgOwner; try { refarg_read($o->child->nullable); } catch (Error $e) { return [$e->getMessage(), (new ReflectionProperty(RefArgOwner::class, 'child'))->isInitialized($o)]; } return 'missing-Error'; });
$rows[] = refarg_case('nested-ref-integer-state', function () { $o = new RefArgOwner; try { refarg_write($o->required->nullable); } catch (Error $e) { return [$e->getMessage(), (new ReflectionProperty(RefArgOwner::class, 'required'))->isInitialized($o)]; } return 'missing-Error'; });
$rows[] = refarg_case('nested-ref-scalar-receivers', function () { $o = new RefArgOwner; $messages = []; foreach ([null, 1, false, 1.5, 'x', []] as $value) { $o->anything = $value; try { refarg_write($o->anything->nullable); } catch (Error $e) { $messages[] = $e->getMessage(); } } return $messages; });
$rows[] = refarg_case('native-ref-child-state', function () { $o = new RefArgOwner; try { str_replace('a', 'b', 'aa', $o->child->nullable); } catch (Error $e) { return [$e->getMessage(), (new ReflectionProperty(RefArgOwner::class, 'child'))->isInitialized($o)]; } return 'missing-Error'; });
$rows[] = refarg_case('initialized-native-current', function () { $o = new RefArgOwner; $o->array = [4, 5]; $f = 'current'; return [$f($o->array), $o->array]; });
$rows[] = refarg_case('initialized-native-key', function () { $o = new RefArgOwner; $o->array = [4, 5]; $f = 'key'; return [$f($o->array), $o->array]; });
$rows[] = refarg_case('initialized-native-in_array', function () { $o = new RefArgOwner; $o->array = [4, 5]; $f = 'in_array'; return [$f(5, $o->array), $o->array]; });
$rows[] = refarg_case('initialized-native-is_array', function () { $o = new RefArgOwner; $o->array = [4, 5]; $f = 'is_array'; return [$f($o->array), $o->array]; });
$rows[] = refarg_case('native-borrow-replacement', function () { $o = new RefArgOwner; $o->array = [1]; return [in_array(2, $o->array, refarg_replace_array($o)), $o->array]; });
$rows[] = refarg_case('dynamic-native-borrow-replacement', function () { $o = new RefArgOwner; $o->array = [1]; $f = 'in_array'; return [$f(2, $o->array, refarg_replace_array($o)), $o->array]; });
$rows[] = refarg_case('native-borrow-mutation', function () { $o = new RefArgOwner; $o->array = [1]; return [in_array(2, $o->array, refarg_mutate_array($o)), $o->array]; });
$rows[] = refarg_case('dynamic-native-borrow-mutation', function () { $o = new RefArgOwner; $o->array = [1]; $f = 'in_array'; return [$f(2, $o->array, refarg_mutate_array($o)), $o->array]; });
$rows[] = refarg_case('native-borrow-reference-element', function () { $o = new RefArgOwner; $v = 1; $o->array = [&$v]; return [in_array(2, $o->array, refarg_mutate_scalar($v)), $v]; });
$rows[] = refarg_case('dynamic-native-borrow-reference-element', function () { $o = new RefArgOwner; $v = 1; $o->array = [&$v]; $f = 'in_array'; return [$f(2, $o->array, refarg_mutate_scalar($v)), $v]; });
$rows[] = refarg_case('native-borrow-magic-getter', function () { $o = new RefArgMagic; return current($o->anything); });
$rows[] = refarg_case('dynamic-native-borrow-magic-getter', function () { $o = new RefArgMagic; $f = 'current'; return $f($o->anything); });
$rows[] = refarg_case('native-borrow-cursor', function () { $o = new RefArgOwner; $o->array = [4, 5]; next($o->array); return [current($o->array), key($o->array), next($o->array)]; });
$rows[] = refarg_case('dynamic-native-borrow-cursor', function () { $o = new RefArgOwner; $o->array = [4, 5]; next($o->array); $f = 'current'; $k = 'key'; return [$f($o->array), $k($o->array), next($o->array)]; });
$rows[] = refarg_case('native-borrow-exhausted-cursor', function () { $o = new RefArgOwner; $o->array = [4, 5]; next($o->array); next($o->array); return [current($o->array), key($o->array)]; });
$rows[] = refarg_case('dynamic-native-borrow-exhausted-cursor', function () { $o = new RefArgOwner; $o->array = [4, 5]; next($o->array); next($o->array); $f = 'current'; $k = 'key'; return [$f($o->array), $k($o->array)]; });
// Array-element SEND_REF is intentionally outside the terminal-property fix:
// refarg_write($o->array[0]) needs a separate array-lvalue fetch context.
return $rows;
