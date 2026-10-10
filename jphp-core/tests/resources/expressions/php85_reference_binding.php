<?php
function referenceArgDetach(&$p) { unset($p); $p = 91; return $p; }
function referenceArgRebind(&$p, &$q) { $p =& $q; $p = 92; return [$p, $q]; }
function &referenceReturnAlias(&$p) { return $p; }
function &referenceReturnLocal() { $x = 3; return $x; }
function &referenceReturnTemporary() { return 3; }
function &referenceReturnSaved(&$p) { static $saved; $saved =& $p; return $saved; }
function referenceVariadic(&...$args) { $args[0] = 93; unset($args[1]); return $args; }
function referenceCase($operation, $initial) {
    $x = $initial; $y = 2; $z = 3; $events = [];
    set_error_handler(function($level, $message) use (&$events) { $events[] = [$level, $message]; return true; });
    try {
        if ($operation === 'basic') { $y =& $x; $y = 7; }
        if ($operation === 'unsetSource') { $y =& $x; unset($x); $x = 8; }
        if ($operation === 'unsetAlias') { $y =& $x; unset($y); $y = 8; }
        if ($operation === 'rebindSource') { $y =& $x; $x =& $z; $x = 8; }
        if ($operation === 'rebindAlias') { $y =& $x; $y =& $z; $y = 8; }
        if ($operation === 'threeAliases') { $y =& $x; $z =& $y; unset($x); $x = 8; $y = 9; }
        if ($operation === 'self') { $y =& $x; $x =& $x; $x = 8; }
        if ($operation === 'detachArgument') { $y = referenceArgDetach($x); }
        if ($operation === 'rebindArgument') { $events[] = referenceArgRebind($x, $y); }
        if ($operation === 'returnArgument') { $y =& referenceReturnAlias($x); unset($x); $x = 8; $y = 9; }
        if ($operation === 'returnSaved') { $y =& referenceReturnSaved($x); unset($x); $x = 8; $y = 9; }
        if ($operation === 'closureCapture') { $f = function() use (&$x) { $x = 10; return $x; }; unset($x); $x = 8; $y = $f(); }
        if ($operation === 'closureDetach') { $f = function() use (&$x) { unset($x); $x = 10; return $x; }; $y = $f(); }
        if ($operation === 'closureRebind') { $f = function() use (&$x, &$z) { $x =& $z; $x = 10; return $x; }; $y = $f(); }
        if ($operation === 'dynamicCall') { $f = 'referenceArgRebind'; $events[] = $f($x, $y); }
        if ($operation === 'reflectionCall') { $f = new ReflectionFunction('referenceArgRebind'); $args = [&$x, &$y]; $events[] = $f->invokeArgs($args); }
        if ($operation === 'callbackCall') { $events[] = call_user_func_array('referenceArgRebind', [&$x, &$y]); }
        if ($operation === 'variadic') { $events[] = referenceVariadic($x, $y); }
        if ($operation === 'literal') { $a = [&$x]; unset($x); $x = 8; $y = $a[0]; }
        if ($operation === 'literalCopy') { $a = [&$x]; $b = $a; $x = 8; unset($a[0]); $y = $b[0]; }
        if ($operation === 'arrayTarget') { $a = ['k'=>1]; $a['k'] =& $x; unset($x); $x = 8; $y = $a['k']; }
        if ($operation === 'propertySource') { $box = new stdClass; $box->p = $initial; $x =& $box->p; unset($box->p); $box->p = 8; $y = $x; $z = $box->p; }
        if ($operation === 'propertyTarget') { $box = new stdClass; $box->p =& $x; unset($x); $x = 8; $y = $box->p; }
        if ($operation === 'foreach') { $a = [1,2]; foreach ($a as &$x) {} unset($x); $x = 8; $y = $a; }
    } catch (Throwable $e) { $events[] = [get_class($e), $e->getMessage()]; }
    restore_error_handler();
    return [$operation, $initial, $x, $y, $z, $events];
}
$rows = [];
foreach (['basic','unsetSource','unsetAlias','rebindSource','rebindAlias','threeAliases','self','detachArgument','rebindArgument','returnArgument','returnSaved','closureCapture','closureDetach','closureRebind','dynamicCall','reflectionCall','callbackCall','variadic','literal','literalCopy','arrayTarget','propertySource','propertyTarget','foreach'] as $operation)
    foreach ([null, 1, 's', [1,2], ['k'=>[3]]] as $initial) $rows[] = referenceCase($operation, $initial);
$x = 1; $y =& $x; unset($x); $x = 8; $rows[] = ['mainUnset', $x, $y];
$x = 1; $y =& $x; $z = 3; $x =& $z; $x = 8; $rows[] = ['mainRebind', $x, $y, $z];
$x = 1; $f = function() use (&$x) { return $x; }; unset($x); $x = 8; $rows[] = ['mainClosure', $x, $f()];
$x = 1; $y =& referenceReturnAlias($x); unset($x); $x = 8; $rows[] = ['mainReturn', $x, $y];
$x = 1; $name = 'x'; $y =& $$name; unset($$name); $x = 8; $rows[] = ['varvarUnset', $x, $y];
$x = 1; $name = 'x'; $y =& $x; $z = 3; $$name =& $z; $z = 8; $rows[] = ['varvarRebind', $x, $y, $z];
$y =& referenceReturnLocal(); $y = 8; $rows[] = ['returnLocal', $y];
function referenceUndefined() { $y =& $x; return [isset($x), $x, $y, gettype($x)]; }
$rows[] = ['undefined', referenceUndefined()];
class ReferenceStaticBox { public static $p = 1; }
$x =& ReferenceStaticBox::$p; $y =& $x; $x =& $z; $z = 8; $rows[] = ['staticRebind', ReferenceStaticBox::$p, $x, $y];
function referenceGlobalDetach() { global $referenceGlobal; unset($referenceGlobal); $referenceGlobal = 10; return $referenceGlobal; }
function referenceGlobalRebind() { global $referenceGlobal, $referenceOther; $referenceGlobal =& $referenceOther; $referenceGlobal = 11; return $referenceGlobal; }
function referenceGlobalDynamic() { $name = 'referenceGlobal'; global $$name; unset($$name); $$name = 12; return $$name; }
function &referenceGlobalReturn() { global $referenceGlobal; return $referenceGlobal; }
$referenceGlobal = 1; $referenceOther = 2; $rows[] = ['globalDetach', referenceGlobalDetach(), $referenceGlobal];
$referenceGlobal = 1; $referenceOther = 2; $rows[] = ['globalRebind', referenceGlobalRebind(), $referenceGlobal, $referenceOther];
$referenceGlobal = 1; $rows[] = ['globalDynamic', referenceGlobalDynamic(), $referenceGlobal];
$referenceGlobal = 1; $alias =& referenceGlobalReturn(); unset($referenceGlobal); $referenceGlobal = 8; $rows[] = ['globalReturn', $referenceGlobal, $alias];
function referenceStaticDetach() { static $s = 5; $before = $s; unset($s); $s = 9; return [$before, $s]; }
function referenceStaticRebind() { static $s = 5; $before = $s; $x = 9; $s =& $x; return [$before, $s]; }
$rows[] = ['staticDetach', referenceStaticDetach(), referenceStaticDetach()];
$rows[] = ['staticRebind', referenceStaticRebind(), referenceStaticRebind()];
function referenceTypedInt(int &$p) { $p += 1; return $p; }
function referenceTypedFloat(float &$p) { $p += 1; return $p; }
function referenceTypedString(string &$p) { $p .= '!'; return $p; }
function referenceTypedBool(bool &$p) { $p = !$p; return $p; }
function referenceTypedVariadic(int &...$args) { $args[0] += 1; $args[1] += 2; return $args; }
foreach (['referenceTypedInt','referenceTypedFloat','referenceTypedString','referenceTypedBool'] as $function) {
    foreach ([1, '7', true] as $initial) {
        $x = $initial; $result = $function($x); $rows[] = ['typed', $function, $initial, $result, $x, gettype($x)];
    }
}
$x = '7'; $y = '8'; $rows[] = ['typedVariadic', referenceTypedVariadic($x,$y), $x, $y];
function &referenceTypedReturn(): int { static $s = '7'; return $s; }
$x =& referenceTypedReturn(); $x = 8; $rows[] = ['typedReturn', $x, referenceTypedReturn()];
class ReferenceCalls {
    public function instance(&$p, &$q) { $p =& $q; $p = 21; return [$p,$q]; }
    public static function method(&$p, &$q) { $p =& $q; $p = 22; return [$p,$q]; }
}
foreach (['instance','method'] as $method) {
    $x = 1; $y = 2; $box = new ReferenceCalls; $reflection = new ReflectionMethod('ReferenceCalls', $method);
    $result = $reflection->invokeArgs($method === 'method' ? null : $box, [&$x,&$y]);
    $rows[] = ['reflectionMethod', $method, $result, $x, $y];
}
function &referenceReturnNull() { return null; }
function &referenceReturnArray() { return [1,2]; }
function &referenceReturnEmpty() {}
function &referenceReturnBare() { return; }
foreach (['referenceReturnTemporary','referenceReturnNull','referenceReturnArray','referenceReturnEmpty','referenceReturnBare'] as $function) {
    $events = [];
    set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,$message]; return true; });
    $x =& $function(); $x = 8;
    restore_error_handler(); $rows[] = ['returnTemporary', $function, $x, $events];
}
$x = 1; $a = [];
for ($i = 0; $i < 20000; $i++) { $a['k'] =& $x; $x++; }
unset($x); $x = 8; $rows[] = ['repeatedExport', $a['k'], $x];
return $rows;
