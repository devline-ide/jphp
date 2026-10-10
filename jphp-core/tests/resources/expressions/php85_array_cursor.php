<?php
$rows = [];
foreach ([-PHP_INT_MAX-1, -5, -1, 0, 1, 2147483648, PHP_INT_MAX-1, PHP_INT_MAX] as $key) {
    foreach (['append', 'unset', 'pop', 'popEmpty', 'stringPop', 'shift', 'shiftEmpty', 'pushTwo', 'unshift', 'unshiftZero', 'pushZero', 'copy', 'copyEmpty', 'increment', 'reference'] as $operation) {
        $a = [$key => 'old']; $log = [];
        try {
            if ($operation === 'unset') unset($a[$key]);
            if ($operation === 'pop') $log[] = array_pop($a);
            if ($operation === 'popEmpty') { $log[] = array_pop($a); $log[] = array_pop($a); }
            if ($operation === 'stringPop') { $a['s'] = 1; $log[] = array_pop($a); }
            if ($operation === 'shift') $log[] = array_shift($a);
            if ($operation === 'shiftEmpty') { unset($a[$key]); $log[] = array_shift($a); }
            if ($operation === 'unshift') $log[] = array_unshift($a, 'prefix', 'prefix2');
            if ($operation === 'unshiftZero') $log[] = array_unshift($a);
            if ($operation === 'pushZero') $log[] = array_push($a);
            if ($operation === 'copy' || $operation === 'copyEmpty') {
                if ($operation === 'copyEmpty') unset($a[$key]);
                $copy = $a;
                $copy[] = 'copy';
                $log[] = $copy;
            }
            if ($operation === 'pushTwo') $log[] = array_push($a, 'first', 'second');
            else if ($operation === 'increment') { $a[]++; $a[]++; }
            else if ($operation === 'reference') { $a[] =& $x; $x = 17; $a[] =& $y; $y = 19; }
            else { $a[] = 'first'; $a[] = 'second'; }
        } catch (Throwable $e) { $log[] = [get_class($e), $e->getMessage()]; }
        $rows[] = [$key, $operation, $a, $log];
        unset($x, $y);
    }
}
foreach (['pop', 'shift', 'unshift', 'unshiftZero', 'push', 'pushZero'] as $operation) {
    $ref = 5; $a = ['s' => &$ref, -9 => [1], 't' => 8, 20 => 2]; $copy = $a; $log = [];
    if ($operation === 'pop') $log[] = array_pop($a);
    if ($operation === 'shift') $log[] = array_shift($a);
    if ($operation === 'unshift') $log[] = array_unshift($a, 10, [20]);
    if ($operation === 'unshiftZero') $log[] = array_unshift($a);
    if ($operation === 'push') $log[] = array_push($a, [30], 40);
    if ($operation === 'pushZero') $log[] = array_push($a);
    $ref = 6; $a[] = 'tail'; $rows[] = [$operation, $a, $copy, $log];
}
foreach ([[], ['s'=>1], [-3=>1,'s'=>2], [4=>1,8=>2,'s'=>3], [PHP_INT_MAX=>1]] as $initial) {
    foreach (['shift', 'unshiftZero', 'unsetAll', 'popAll'] as $operation) {
        $a = $initial; $log = [];
        try {
            if ($operation === 'shift') $log[] = array_shift($a);
            if ($operation === 'unshiftZero') $log[] = array_unshift($a);
            if ($operation === 'unsetAll') foreach ($a as $k => $v) unset($a[$k]);
            if ($operation === 'popAll') while (count($a)) $log[] = array_pop($a);
            $a[] = 2;
        } catch (Throwable $e) { $log[] = [get_class($e), $e->getMessage()]; }
        $rows[] = [$operation, $initial, $a, $log];
    }
}
try { $a = [PHP_INT_MAX => 1, 2]; $rows[] = $a; }
catch (Throwable $e) { $rows[] = ['literal', get_class($e), $e->getMessage()]; }
try { $a = [PHP_INT_MAX => 1, ...[2]]; $rows[] = $a; }
catch (Throwable $e) { $rows[] = ['literalSpread', get_class($e), $e->getMessage()]; }
$a = [PHP_INT_MAX => 1]; unset($a[PHP_INT_MAX]); $a[] = 2; $rows[] = ['reuseMax', $a];
$a = [-3=>1]; $a['s'] = 2; $a[-3] = 3; $a[] = 4; $rows[] = ['overwrite', $a];
$a = [0=>1, 1=>2]; unset($a[1]); $a[] = 3; $rows[] = ['packedUnset', $a];
$a = [1]; $b = $a; array_push($a, $a); $rows[] = ['pushSelf', $a, $b];
$a = [1]; $b = $a; array_unshift($a, $a); $rows[] = ['unshiftSelf', $a, $b];
foreach (['pop', 'shift', 'unshift', 'unshiftZero'] as $operation) {
    $a = [2=>1, 's'=>2, 9=>3]; next($a);
    if ($operation === 'pop') array_pop($a);
    if ($operation === 'shift') array_shift($a);
    if ($operation === 'unshift') array_unshift($a, 4);
    if ($operation === 'unshiftZero') array_unshift($a);
    $rows[] = ['pointer', $operation, key($a), current($a), $a];
}
function cursorReferenceLocal() { $a = [-3=>1]; $a[] =& $x; $x = 9; unset($x); $x = 11; return [$a, $x]; }
$rows[] = ['localReference', cursorReferenceLocal()];
$a = [-3=>1]; $a[] =& $x; $x = 9; unset($x); $x = 11; $rows[] = ['globalReference', $a, $x];
$ref = 5; $a = [0=>&$ref, 1=>[2], 2=>&$ref]; array_shift($a); $a[0][0] = 3; $ref = 6; $rows[] = ['shiftReferences', $a, $ref];
$ref = 5; $a = [-3=>&$ref, 's'=>[2], 2=>&$ref]; array_unshift($a, 1); $a['s'][0] = 3; $ref = 6; $rows[] = ['unshiftReferences', $a, $ref];
return $rows;
