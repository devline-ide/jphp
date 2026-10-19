<?php
function g_before($seed = 4) { $sent = yield $seed; yield $seed + 2; return $sent; }
function ordinary_returns_generator() { return g_before(); }
function unreachable_generator() { if (false) { yield 1; } return 2; }
class GeneratorBase {
    function instanceGen($seed = 4) { $sent = yield $seed; yield $seed + 2; return $sent; }
    static function staticGen($seed = 4) { $sent = yield $seed; yield $seed + 2; return $sent; }
    function ordinary() { return g_before(); }
}
class GeneratorChild extends GeneratorBase {}
trait GeneratorTrait { function traitGen($seed = 4) { $sent = yield $seed; yield $seed + 2; return $sent; } }
class GeneratorConsumer { use GeneratorTrait { traitGen as aliasGen; } }
if (true) { class GeneratorDeferred { function deferredGen($seed = 4) { $sent = yield $seed; yield $seed + 2; return $sent; } } }
$base = 4;
$generatorClosure = function () use ($base) { $sent = yield $base; yield $base + 2; return $sent; };
$plainClosure = function () { return g_before(); };
function g_after($seed = 4) { $sent = yield $seed; yield $seed + 2; return $sent; }
$object = new GeneratorChild();
$consumer = new GeneratorConsumer();
$deferred = new GeneratorDeferred();
$rows = [
    'function-before' => new ReflectionFunction('g_before'),
    'function-after' => new ReflectionFunction('g_after'),
    'plain-function' => new ReflectionFunction('ordinary_returns_generator'),
    'unreachable-yield' => new ReflectionFunction('unreachable_generator'),
    'instance' => new ReflectionMethod('GeneratorBase', 'instanceGen'),
    'static' => new ReflectionMethod('GeneratorBase', 'staticGen'),
    'inherited' => new ReflectionMethod('GeneratorChild', 'instanceGen'),
    'plain-method' => new ReflectionMethod('GeneratorBase', 'ordinary'),
    'trait' => new ReflectionMethod('GeneratorTrait', 'traitGen'),
    'trait-alias' => new ReflectionMethod('GeneratorConsumer', 'aliasGen'),
    'deferred' => new ReflectionMethod('GeneratorDeferred', 'deferredGen'),
    'closure' => new ReflectionFunction($generatorClosure),
    'plain-closure' => new ReflectionFunction($plainClosure),
    'first-class-function' => new ReflectionFunction(g_before(...)),
    'first-class-instance' => new ReflectionFunction($object->instanceGen(...)),
    'first-class-static' => new ReflectionFunction(GeneratorBase::staticGen(...)),
    'reflection-closure' => new ReflectionFunction((new ReflectionFunction('g_before'))->getClosure()),
    'reflection-method-closure' => new ReflectionFunction((new ReflectionMethod('GeneratorBase', 'instanceGen'))->getClosure($object)),
    'plain-first-class' => new ReflectionFunction(ordinary_returns_generator(...)),
    'internal' => new ReflectionFunction('strlen'),
];
foreach ($rows as $label => $reflection) { echo $label, ':', $reflection->isGenerator() ? 'true' : 'false', ':', gettype($reflection->isGenerator()), "\n"; }
$factories = [g_before(...), g_after(...), $generatorClosure, $object->instanceGen(...), GeneratorBase::staticGen(...), $consumer->aliasGen(...), $deferred->deferredGen(...)];
foreach ($factories as $index => $factory) {
    $g = $factory(); echo 'run-', $index, ':', $g->current(), ':', $g->send(7), ':';
    $g->next(); echo $g->valid() ? 'valid' : 'done', ':', $g->getReturn(), "\n";
}
$empty = unreachable_generator(); echo 'empty:', $empty->valid() ? 'valid' : 'done', ':', $empty->getReturn(), "\n";
echo 'return-generator:', ordinary_returns_generator()->current(), "\n";

interface GeneratorContract { function generation(); }
abstract class GeneratorAbstract { abstract function abstractGeneration(); }
$nestedFactory = function ($seed = 11) { return function () use ($seed) { $sent = yield $seed; yield $seed + 3; return $sent + 4; }; };
$nestedGenerator = $nestedFactory();
$nestedPlain = function () { return $nested = function () { return g_before(); }; };
$unreachableClosure = function () { if (false) { yield 19; } return 23; };
function &reference_generator() { $cell = 31; yield $cell; return $cell; }
$extraRows = [
    'interface-declaration' => new ReflectionMethod('GeneratorContract', 'generation'),
    'abstract-declaration' => new ReflectionMethod('GeneratorAbstract', 'abstractGeneration'),
    'nested-outer' => new ReflectionFunction($nestedFactory),
    'nested-inner' => new ReflectionFunction($nestedGenerator),
    'nested-plain-outer' => new ReflectionFunction($nestedPlain),
    'nested-plain-inner' => new ReflectionFunction($nestedPlain()),
    'unreachable-closure' => new ReflectionFunction($unreachableClosure),
    'reference-generator' => new ReflectionFunction('reference_generator'),
];
foreach ($extraRows as $label => $reflection) { echo $label, ':', $reflection->isGenerator() ? 'true' : 'false', ':', gettype($reflection->isGenerator()), "\n"; }
$nestedRun = $nestedGenerator(); echo 'nested-run:', $nestedRun->current(), ':', $nestedRun->send(9), ':';
$nestedRun->next(); echo $nestedRun->valid() ? 'valid' : 'done', ':', $nestedRun->getReturn(), "\n";
$unreachableRun = $unreachableClosure(); echo 'unreachable-closure-run:', $unreachableRun->valid() ? 'valid' : 'done', ':', $unreachableRun->getReturn(), "\n";

class MagicGeneratorReflection {
    function __call($name, $args) { yield 41; return 42; }
    static function __callStatic($name, $args) { yield 51; return 52; }
}
$magicObject = new MagicGeneratorReflection();
$magicInstance = $magicObject->missing(...);
$magicStatic = MagicGeneratorReflection::missing(...);
$magicRows = [
    'magic-instance-target' => new ReflectionMethod('MagicGeneratorReflection', '__call'),
    'magic-static-target' => new ReflectionMethod('MagicGeneratorReflection', '__callStatic'),
    'magic-instance-capture' => new ReflectionFunction($magicInstance),
    'magic-static-capture' => new ReflectionFunction($magicStatic),
];
foreach ($magicRows as $label => $reflection) { echo $label, ':', $reflection->isGenerator() ? 'true' : 'false', ':', gettype($reflection->isGenerator()), "\n"; }
foreach (['magic-instance-run' => $magicInstance, 'magic-static-run' => $magicStatic] as $label => $factory) {
    $run = $factory(); echo $label, ':', $run->current(), ':', $run->send(7), ':', $run->valid() ? 'valid' : 'done', ':', $run->getReturn(), "\n";
}
