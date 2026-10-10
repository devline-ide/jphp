<?php
class VariadicArgumentStore {
    private static $values = [];
    function data(...$arguments) {
        if (count($arguments) === 1) return self::$values[$arguments[0]] ?? null;
        $old = self::$values[$arguments[0]] ?? null;
        self::$values[$arguments[0]] = $arguments[1];
        return $old;
    }
}
class NamedArgumentStore {
    private static $values = [];
    function data($key, $value = null) {
        if (func_num_args() === 1) return self::$values[$key] ?? null;
        $old = self::$values[$key] ?? null;
        self::$values[$key] = $value;
        return $old;
    }
}
function argumentStoreRun($class) {
    $first = new $class(); $second = new $class();
    $first->data('count', 0);
    for ($index = 0; $index < 4; $index++) {
        $previous = $first->data('count');
        $first->data('count', $previous + 1);
        if ($second->data('count') !== $index + 1) throw new Exception('Repeated getter changed its argument');
    }
    $first->data('count', 0);
    $callback = function () use ($first) {
        $previous = $first->data('count');
        $first->data('count', $previous + 1);
    };
    for ($index = 0; $index < 4; $index++) $callback();
    if ($second->data('count') !== 4) throw new Exception('Closure call arguments changed');
}
argumentStoreRun(VariadicArgumentStore::class);
argumentStoreRun(NamedArgumentStore::class);
argumentStoreRun(VariadicArgumentStore::class);
function argumentReferences(&...$values) { foreach ($values as &$value) $value++; }
$left = 1; $right = 2;
argumentReferences($left, $right); argumentReferences($left, $right);
if ($left !== 3 || $right !== 4) throw new Exception('Argument isolation lost references');
return 'arguments-preserved';
