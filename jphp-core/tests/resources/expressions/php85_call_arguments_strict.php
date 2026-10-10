<?php
declare(strict_types=1);
function argumentValue($p) { return is_object($p) ? ['object',get_class($p)] : $p; }
function argumentInt(int $p) { return [gettype($p),argumentValue($p)]; }
function argumentFloat(float $p) { return [gettype($p),argumentValue($p)]; }
function argumentString(string $p) { return [gettype($p),argumentValue($p)]; }
function argumentBool(bool $p) { return [gettype($p),argumentValue($p)]; }
function argumentArray(array $p) { return [gettype($p),argumentValue($p)]; }
function argumentObject(object $p) { return [gettype($p),argumentValue($p)]; }
function argumentCallable(callable $p) { return [gettype($p),argumentValue($p)]; }
function argumentIterable(iterable $p) { return [gettype($p),argumentValue($p)]; }
function argumentClass(ArgumentBox $p) { return [gettype($p),argumentValue($p)]; }
function argumentNullable(?int $p) { return [gettype($p),argumentValue($p)]; }
function argumentDefault(int $p = 5) { return [gettype($p),argumentValue($p)]; }
function argumentReference(int &$p) { $p += 1; return [gettype($p),$p]; }
function argumentTail(int ...$p) { return $p; }
function argumentReferenceTail(int &...$p) { foreach ($p as &$v) $v += 1; return $p; }
function argumentConstant(int $p) { return 42; }
function argumentExact($a,$b) { return [$a,$b]; }
function argumentOptional($a,$b=2) { return [$a,$b]; }
function argumentMinimum($a,$b,...$tail) { return [$a,$b,$tail]; }
function argumentErrorMessage($message) {
    if (strpos($message,'{closure:') === 0) $message = '{closure}' . substr($message,strpos($message,'}')+1);
    $where = strpos($message, ', called in ');
    if ($where !== false) $message = substr($message,0,$where);
    if (strpos($message, 'Too few arguments to function ') === 0) {
        $where = strpos($message, ' passed in '); $end = strpos($message, ' and ');
        if ($where !== false && $end !== false) $message = substr($message,0,$where) . ' passed' . substr($message,$end);
    }
    return $message;
}
class ArgumentBox {
    public function method(int $p) { return [gettype($p),$p]; }
    public static function staticMethod(int $p) { return [gettype($p),$p]; }
    public function reference(int &$p) { $p += 1; return [gettype($p),$p]; }
    public static function staticReference(int &$p) { $p += 1; return [gettype($p),$p]; }
}
class ArgumentChild extends ArgumentBox {}
class ArgumentStringBox { public function __toString() { return '7'; } }
function argumentKnown($function,$x) {
    switch ($function) {
        case 'argumentInt': return argumentInt($x);
        case 'argumentFloat': return argumentFloat($x);
        case 'argumentString': return argumentString($x);
        case 'argumentBool': return argumentBool($x);
        case 'argumentArray': return argumentArray($x);
        case 'argumentObject': return argumentObject($x);
        case 'argumentCallable': return argumentCallable($x);
        case 'argumentIterable': return argumentIterable($x);
        case 'argumentClass': return argumentClass($x);
        case 'argumentNullable': return argumentNullable($x);
        case 'argumentDefault': return argumentDefault($x);
        case 'argumentConstant': return argumentConstant($x);
        case 'argumentReference': return argumentReference($x);
        case 'argumentTail': return argumentTail($x);
        case 'argumentReferenceTail': return argumentReferenceTail($x);
    }
}
function argumentCase($mode,$function,$initial) {
    $x = $initial; $events = []; $result = null;
    set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,argumentErrorMessage($message)]; return true; });
    try {
        if ($mode === 'known') $result = argumentKnown($function,$x);
        if ($mode === 'dynamic') $result = $function($x);
        if ($mode === 'spread') $result = $function(...[$x]);
        if ($mode === 'callback') $result = call_user_func($function,$x);
        if ($mode === 'callbackArray') $result = call_user_func_array($function,[$x]);
        if ($mode === 'callbackReferences') $result = call_user_func_array($function,[&$x]);
        if ($mode === 'reflection') $result = (new ReflectionFunction($function))->invoke($x);
        if ($mode === 'reflectionArray') $result = (new ReflectionFunction($function))->invokeArgs([$x]);
        if ($mode === 'reflectionReferences') $result = (new ReflectionFunction($function))->invokeArgs([&$x]);
    } catch (Throwable $e) { $events[] = [get_class($e),argumentErrorMessage($e->getMessage())]; }
    restore_error_handler(); return [$mode,$function,argumentValue($initial),$result,argumentValue($x),$events];
}
$rows = [];
$modes = ['known','dynamic','spread','callback','callbackArray','callbackReferences','reflection','reflectionArray','reflectionReferences'];
foreach ($modes as $mode)
    foreach (['argumentInt','argumentFloat','argumentString','argumentBool','argumentArray','argumentObject','argumentCallable','argumentIterable','argumentClass','argumentNullable','argumentDefault','argumentReference','argumentTail','argumentReferenceTail','argumentConstant'] as $function)
        foreach ([null,false,true,1,7.5,'7','7.5','text',[1,2],new stdClass,new ArgumentBox,new ArgumentStringBox,'argumentInt'] as $initial)
            $rows[] = argumentCase($mode,$function,$initial);
foreach (['dynamic','spread','callbackArray','callbackReferences','reflectionArray','reflectionReferences'] as $mode) {
    foreach (['argumentExact','argumentOptional','argumentMinimum','argumentConstant'] as $function) {
        foreach ([0,1,2,3] as $count) {
            $args = array_slice([1,2,3],0,$count); $events = []; $result = null;
            try {
                if ($mode === 'dynamic' || $mode === 'spread') $result = $function(...$args);
                if ($mode === 'callbackArray' || $mode === 'callbackReferences') $result = call_user_func_array($function,$args);
                if ($mode === 'reflectionArray' || $mode === 'reflectionReferences') $result = (new ReflectionFunction($function))->invokeArgs($args);
            } catch (Throwable $e) { $events[] = [get_class($e),argumentErrorMessage($e->getMessage())]; }
            $rows[] = ['count',$mode,$function,$count,$result,$events];
        }
    }
}
foreach (['method','staticMethod','reference','staticReference'] as $method)
    foreach (['dynamic','callback','callbackArray','reflection','reflectionArray'] as $mode)
        foreach ([null,1,'7',7.5,'bad'] as $initial) {
            $x = $initial; $events = []; $result = null; $box = new ArgumentChild;
            $static = $method === 'staticMethod' || $method === 'staticReference';
            $callback = $static ? ['ArgumentChild',$method] : [$box,$method];
            $reflector = new ReflectionMethod('ArgumentChild',$method);
            set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,argumentErrorMessage($message)]; return true; });
            try {
                if ($mode === 'dynamic') { if ($static) $result = ArgumentChild::$method($x); else $result = $box->$method($x); }
                if ($mode === 'callback') $result = call_user_func($callback,$x);
                if ($mode === 'callbackArray') $result = call_user_func_array($callback,[&$x]);
                if ($mode === 'reflection') $result = $reflector->invoke($static ? null : $box,$x);
                if ($mode === 'reflectionArray') $result = $reflector->invokeArgs($static ? null : $box,[&$x]);
            } catch (Throwable $e) { $events[] = [get_class($e),argumentErrorMessage($e->getMessage())]; }
            restore_error_handler(); $rows[] = ['method',$method,$mode,$initial,$result,$x,$events];
        }
foreach (['argumentTail','argumentReferenceTail'] as $function) {
    foreach (['dynamic','callbackArray','reflectionArray'] as $mode) {
        foreach ([0,1,2] as $position) {
            $args = [1,2,3]; $args[$position] = 'bad'; $events = [];
            set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,argumentErrorMessage($message)]; return true; });
            try {
                if ($mode === 'dynamic') $function(...$args);
                if ($mode === 'callbackArray') call_user_func_array($function,$args);
                if ($mode === 'reflectionArray') (new ReflectionFunction($function))->invokeArgs($args);
            } catch (Throwable $e) { $events[] = [get_class($e),argumentErrorMessage($e->getMessage())]; }
            restore_error_handler(); $rows[] = ['variadicIndex',$function,$mode,$position,$events];
        }
    }
}
function argumentAlias(&$p) { $p = 9; return $p; }
function argumentLiteral($operation,$dynamic) {
    $fn = $dynamic ? 'argumentAlias' : null; $events = []; $result = null;
    set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,argumentErrorMessage($message)]; return true; });
    try {
        if ($dynamic) {
            if ($operation === 'int') $result = $fn(1);
            if ($operation === 'bool') $result = $fn(false);
            if ($operation === 'null') $result = $fn(null);
            if ($operation === 'string') $result = $fn('s');
            if ($operation === 'array') $result = $fn([1,2]);
            if ($operation === 'expression') $result = $fn(1+2);
            if ($operation === 'object') $result = $fn(new stdClass);
        } else {
            if ($operation === 'int') $result = argumentAlias(1);
            if ($operation === 'bool') $result = argumentAlias(false);
            if ($operation === 'null') $result = argumentAlias(null);
            if ($operation === 'string') $result = argumentAlias('s');
            if ($operation === 'array') $result = argumentAlias([1,2]);
            if ($operation === 'expression') $result = argumentAlias(1+2);
            if ($operation === 'object') $result = argumentAlias(new stdClass);
        }
    } catch (Throwable $e) { $events[] = [get_class($e),argumentErrorMessage($e->getMessage())]; }
    restore_error_handler(); return ['literal',$operation,$dynamic,$result,$events];
}
foreach ([false,true] as $dynamic)
    foreach (['int','bool','null','string','array','expression','object'] as $operation) $rows[] = argumentLiteral($operation,$dynamic);
class ArgumentCacheA {
    private static function secret($p) { return ['Asecret',$p]; }
    protected static function protectedMethod($p) { return ['Aprotected',$p]; }
    public static function first($p) { return ['Afirst',$p]; }
    public static function second($p) { return ['Asecond',$p]; }
    public function instance($p) { return ['instance',$p]; }
    public static function __callStatic($method,$args) { return ['Amagic',$method,$args]; }
}
class ArgumentCacheB {
    private static function secret($p) { return ['Bsecret',$p]; }
    public static function first($p) { return ['Bfirst',$p]; }
    public static function second($p) { return ['Bsecond',$p]; }
}
$entry = function($class,$method,$x) { return $class::$method($x); };
$entryA = $entry->bindTo(null,'ArgumentCacheA'); $entryB = $entry->bindTo(null,'ArgumentCacheB');
foreach ([0,1] as $round)
    foreach (['A','B','global','A'] as $scope)
        foreach (['ArgumentCacheA','ArgumentCacheB'] as $class)
            foreach (['first','second','first','secret','protectedMethod','instance','missing'] as $method) {
                $fn = $scope === 'A' ? $entryA : ($scope === 'B' ? $entryB : $entry); $events = []; $result = null;
                try { $result = $fn($class,$method,3); }
                catch (Throwable $e) { $events[] = [get_class($e),argumentErrorMessage($e->getMessage())]; }
                $rows[] = ['cache',$round,$scope,$class,$method,$result,$events];
            }
return $rows;
