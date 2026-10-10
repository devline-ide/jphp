<?php
function indirectCollect(...$args) { return $args; }
function indirectOne(&$p) { $p = ['changed']; return $p; }
function indirectTwo(&$p, &$q) { $p = 71; $q = 72; return [$p, $q]; }
function indirectTail($first, &...$tail) { foreach ($tail as &$p) $p = 73; return [$first, $tail]; }
function indirectTyped(int &$p) { $p += 1; return $p; }
function indirectObject(&$p) { $p->v = 74; $p = new stdClass; $p->v = 75; return $p->v; }
class IndirectBox {
    public function collect(...$args) { return [static::class, $args]; }
    public static function staticCollect(...$args) { return [static::class, $args]; }
    public function mutate(&$p, &$q) { return indirectTwo($p, $q); }
    public static function staticMutate(&$p, &$q) { return indirectTwo($p, $q); }
    private function hidden($p) { return ['private', $p]; }
    protected static function protectedMethod($p) { return ['protected', static::class, $p]; }
}
class IndirectChild extends IndirectBox {
    public function collect(...$args) { return ['child', $args]; }
}
function indirectCase($mode, $function, $references, $initial) {
    $x = $initial; $y = 2; $events = []; $result = null;
    $args = $references ? [&$x, &$y] : [$x, $y];
    set_error_handler(function($level, $message) use (&$events) { $events[] = [$level, $message]; return true; });
    try {
        if ($mode === 'invoke') $result = (new ReflectionFunction($function))->invoke($x, $y);
        if ($mode === 'invokeArgs') $result = (new ReflectionFunction($function))->invokeArgs($args);
        if ($mode === 'callback') $result = call_user_func($function, $x, $y);
        if ($mode === 'callbackArray') $result = call_user_func_array($function, $args);
    } catch (Throwable $e) { $events[] = [get_class($e), $e->getMessage()]; }
    restore_error_handler();
    return [$mode, $function, $references, $initial, $result, $x, $y, $events];
}
$rows = [];
foreach (['invoke','invokeArgs','callback','callbackArray'] as $mode)
    foreach (['indirectOne','indirectTwo','indirectTail'] as $function)
        foreach ([false,true] as $references)
            foreach ([null,1,'s',[1,2]] as $initial) $rows[] = indirectCase($mode, $function, $references, $initial);
foreach (['invoke','invokeArgs','callback','callbackArray'] as $mode)
    foreach ([false,true] as $references)
        foreach ([1,'7',true] as $initial) $rows[] = indirectCase($mode, 'indirectTyped', $references, $initial);
foreach (['invoke','invokeArgs','callback','callbackArray'] as $mode) {
    foreach ([false,true] as $references) {
        $x = new stdClass; $x->v = 1; $args = $references ? [&$x] : [$x]; $events = [];
        set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,$message]; return true; });
        if ($mode === 'invoke') $r = (new ReflectionFunction('indirectObject'))->invoke($x);
        if ($mode === 'invokeArgs') $r = (new ReflectionFunction('indirectObject'))->invokeArgs($args);
        if ($mode === 'callback') $r = call_user_func('indirectObject', $x);
        if ($mode === 'callbackArray') $r = call_user_func_array('indirectObject', $args);
        restore_error_handler(); $rows[] = ['object', $mode, $references, $r, $x->v, $events];
    }
}
foreach (['collect','staticCollect'] as $method) {
    foreach (['IndirectBox','IndirectChild'] as $class) {
        foreach ([null,new IndirectBox,new IndirectChild,new stdClass] as $object) {
            foreach ([0,1,2,4] as $count) {
                foreach (['invoke','invokeArgs'] as $mode) {
                    $r = new ReflectionMethod($class, $method); $result = null; $events = [];
                    $args = [1,2,3,4]; $args = array_slice($args,0,$count);
                    try {
                        if ($mode === 'invoke') $result = $r->invoke($object, ...$args);
                        else $result = $r->invokeArgs($object, $args);
                    } catch (Throwable $e) { $events[] = [get_class($e),$e->getMessage()]; }
                    $rows[] = ['arity', $method, $class, $object === null ? null : get_class($object), $count, $mode, $result, $events];
                }
            }
        }
    }
}
foreach (['mutate','staticMutate'] as $method)
    foreach (['invoke','invokeArgs','callback','callbackArray'] as $mode)
        foreach ([false,true] as $references) {
            $box = new IndirectChild; $x = 1; $y = 2; $args = $references ? [&$x,&$y] : [$x,$y]; $events = [];
            $r = new ReflectionMethod('IndirectBox',$method); $object = $method === 'staticMutate' ? null : $box;
            $callback = $method === 'staticMutate' ? ['IndirectChild',$method] : [$box,$method];
            set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,$message]; return true; });
            if ($mode === 'invoke') $result = $r->invoke($object,$x,$y);
            if ($mode === 'invokeArgs') $result = $r->invokeArgs($object,$args);
            if ($mode === 'callback') $result = call_user_func($callback,$x,$y);
            if ($mode === 'callbackArray') $result = call_user_func_array($callback,$args);
            restore_error_handler(); $rows[] = ['methodReferences',$method,$mode,$references,$result,$x,$y,$events];
        }
foreach (['hidden','protectedMethod'] as $method) {
    $r = new ReflectionMethod('IndirectBox',$method); $object = $method === 'hidden' ? new IndirectChild : null;
    $rows[] = ['visibility', $method, $r->invoke($object,4), $r->invokeArgs($object,[5])];
}
$reflection = new ReflectionClass('IndirectChild');
$rows[] = ['classMethod', $reflection->getMethod('staticCollect')->invoke(null,1,2)];
foreach ($reflection->getMethods() as $method)
    if ($method->getName() === 'staticCollect') $rows[] = ['classMethods', $method->invokeArgs(null,[3,4])];
$rows[] = ['emptyFunction', (new ReflectionFunction('indirectCollect'))->invoke(), (new ReflectionFunction('indirectCollect'))->invokeArgs([])];
// Nested calls in a warning handler must not inherit a temporary reference policy.
$events = []; $x = 1;
set_error_handler(function($level,$message) use (&$events) {
    $value = 2; $nested = (new ReflectionFunction('indirectOne'))->invokeArgs([&$value]);
    $events[] = [$level,$message,$nested,$value]; return true;
});
$r = (new ReflectionFunction('indirectOne'))->invoke($x);
restore_error_handler(); $rows[] = ['nestedWarning',$r,$x,$events];
function indirectWarning($level, $message, &$events) {
    if (strpos($message, '{closure:') === 0) $message = '{closure}' . substr($message, strpos($message, '}') + 1);
    $events[] = [$level,$message]; return true;
}
class IndirectCallable { public function __invoke(&$p) { $p = 76; return $p; } }
foreach (['invoke','invokeArgs','callback','callbackArray'] as $mode) {
    foreach (['closure','object'] as $kind) {
        foreach ([false,true] as $references) {
            if ($kind === 'object' && ($mode === 'invoke' || $mode === 'invokeArgs')) continue;
            $fn = $kind === 'closure' ? function(&$p) { $p = 77; return $p; } : new IndirectCallable;
            $x = 1; $events = []; $args = $references ? [&$x] : [$x];
            set_error_handler(function($level,$message) use (&$events) { return indirectWarning($level,$message,$events); });
            if ($mode === 'invoke') $result = (new ReflectionFunction($fn))->invoke($x);
            if ($mode === 'invokeArgs') $result = (new ReflectionFunction($fn))->invokeArgs($args);
            if ($mode === 'callback') $result = call_user_func($fn,$x);
            if ($mode === 'callbackArray') $result = call_user_func_array($fn,$args);
            restore_error_handler(); $rows[] = ['callable',$mode,$kind,$references,$result,$x,$events];
        }
    }
    foreach ([false,true] as $references) {
        $a = 1; $b = 2; $c = 3; $d = 4; $events = [];
        $args = $references ? [&$a,&$b,&$c,&$d] : [$a,$b,$c,$d];
        set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,$message]; return true; });
        if ($mode === 'invoke') $result = (new ReflectionFunction('indirectTail'))->invoke($a,$b,$c,$d);
        if ($mode === 'invokeArgs') $result = (new ReflectionFunction('indirectTail'))->invokeArgs($args);
        if ($mode === 'callback') $result = call_user_func('indirectTail',$a,$b,$c,$d);
        if ($mode === 'callbackArray') $result = call_user_func_array('indirectTail',$args);
        restore_error_handler(); $rows[] = ['variadic',$mode,$references,$result,$a,$b,$c,$d,$events];
    }
    $events = []; $x = 1; $y = 2;
    set_error_handler(function($level,$message) use (&$events) { $events[] = [$level,$message]; throw new Exception('handler'); });
    try {
        if ($mode === 'invoke') (new ReflectionFunction('indirectTwo'))->invoke($x,$y);
        if ($mode === 'invokeArgs') (new ReflectionFunction('indirectTwo'))->invokeArgs([$x,$y]);
        if ($mode === 'callback') call_user_func('indirectTwo',$x,$y);
        if ($mode === 'callbackArray') call_user_func_array('indirectTwo',[$x,$y]);
    } catch (Throwable $e) { $events[] = [get_class($e),$e->getMessage()]; }
    restore_error_handler(); $rows[] = ['throwingWarning',$mode,$x,$y,$events];
}
return $rows;
