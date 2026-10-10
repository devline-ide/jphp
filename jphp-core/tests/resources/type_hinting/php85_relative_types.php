<?php
function relative_capture($label, $call) {
    try {
        $value = $call();
        return [$label, 'value', is_object($value) ? get_class($value) : $value];
    } catch (Throwable $error) {
        return [$label, 'error', get_class($error)];
    }
}
function relative_never(): never {}
function relative_never_throw(): never { throw new Exception('original'); }
function relative_never_dynamic($value): never { if ($value) throw new Exception('original'); }
function relative_never_finally($value): never {
    try { throw new Exception('original'); }
    finally { $value->count++; }
}
class RelativeRoot { static function rootFactory(): static { return new static; } }
class RelativeBase extends RelativeRoot {
    function lexical(self $x, parent $y): parent { return $y; }
    function receiver($value): static { return $value; }
    function nullable($value): ?static { return $value; }
    static function factory(): static { return new static; }
    static function wrong(): static { return new self; }
    static function forward(): static { return self::factory(); }
    static function parentForward(): static { return parent::rootFactory(); }
    function dynamicParent(): static { return parent::rootFactory(); }
    static function parentFactory(): parent { return new RelativeRoot; }
    static function staticClosure() { return static function($value): static { return $value; }; }
    static function factoryClosure() { return static function(): static { return new static; }; }
    function boundClosure() { return function($value): static { return $value; }; }
}
class RelativeChild extends RelativeBase {}
class RelativeGrandchild extends RelativeChild {}
trait RelativeTrait {
    function traitSelf(self $value): self { return $value; }
    function traitParent(parent $value): parent { return $value; }
    function traitStatic($value): static { return $value; }
}
class RelativeOne extends RelativeRoot { use RelativeTrait; }
class RelativeTwo extends RelativeBase { use RelativeTrait { traitSelf as selfAlias; } }
class RelativeOneChild extends RelativeOne {}
$base = new RelativeBase; $child = new RelativeChild; $grandchild = new RelativeGrandchild; $root = new RelativeRoot;
$rows = [];
foreach ([$base, $child] as $receiver) {
    foreach ([$base, $child, $grandchild, $root, null] as $value) {
        $label = get_class($receiver) . '/' . (is_object($value) ? get_class($value) : 'null');
        $rows[] = relative_capture('static/' . $label, function() use ($receiver, $value) { return $receiver->receiver($value); });
        $rows[] = relative_capture('nullable/' . $label, function() use ($receiver, $value) { return $receiver->nullable($value); });
    }
}
$rows[] = relative_capture('factory/base', function() { return RelativeBase::factory(); });
$rows[] = relative_capture('factory/child', function() { return RelativeChild::factory(); });
$rows[] = relative_capture('wrong/base', function() { return RelativeBase::wrong(); });
$rows[] = relative_capture('wrong/child', function() { return RelativeChild::wrong(); });
$rows[] = relative_capture('forward/child', function() { return RelativeChild::forward(); });
$rows[] = relative_capture('forward/parent-child', function() { return RelativeChild::parentForward(); });
$rows[] = relative_capture('forward/dynamic-parent', function() use ($child) { return $child->dynamicParent(); });
$rows[] = relative_capture('parent/child', function() { return RelativeChild::parentFactory(); });
$rows[] = relative_capture('lexical/valid', function() use ($child, $base, $root) { return $child->lexical($base, $root); });
$rows[] = relative_capture('lexical/invalid', function() use ($child, $root) { return $child->lexical($root, $root); });
$baseClosure = RelativeBase::staticClosure(); $childClosure = RelativeChild::staticClosure();
foreach ([$base, $child, $grandchild] as $value) {
    $rows[] = relative_capture('static-closure/base/' . get_class($value), function() use ($baseClosure, $value) { return $baseClosure($value); });
    $rows[] = relative_capture('static-closure/child/' . get_class($value), function() use ($childClosure, $value) { return $childClosure($value); });
}
$rows[] = relative_capture('factory-closure/child', RelativeChild::factoryClosure());
$bound = $child->boundClosure();
$rows[] = relative_capture('bound-closure/child', function() use ($bound, $child) { return $bound($child); });
$rows[] = relative_capture('bound-closure/base', function() use ($bound, $base) { return $bound($base); });
$one = new RelativeOne; $two = new RelativeTwo; $oneChild = new RelativeOneChild;
$rows[] = relative_capture('trait/self-one', function() use ($one) { return $one->traitSelf($one); });
$rows[] = relative_capture('trait/self-two', function() use ($two) { return $two->traitSelf($two); });
$rows[] = relative_capture('trait/self-two-invalid', function() use ($two, $one) { return $two->traitSelf($one); });
$rows[] = relative_capture('trait/self-inherited', function() use ($oneChild, $one) { return $oneChild->traitSelf($one); });
$rows[] = relative_capture('trait/alias', function() use ($two) { return $two->selfAlias($two); });
$rows[] = relative_capture('trait/parent-one', function() use ($one, $root) { return $one->traitParent($root); });
$rows[] = relative_capture('trait/parent-two', function() use ($two, $base) { return $two->traitParent($base); });
$rows[] = relative_capture('trait/parent-two-invalid', function() use ($two, $root) { return $two->traitParent($root); });
$rows[] = relative_capture('trait/static-child', function() use ($oneChild, $one) { return $oneChild->traitStatic($one); });
$rows[] = relative_capture('never/empty', 'relative_never');
$rows[] = relative_capture('never/throw', 'relative_never_throw');
$rows[] = relative_capture('never/dynamic-false', function() { return relative_never_dynamic(false); });
$rows[] = relative_capture('never/dynamic-true', function() { return relative_never_dynamic(true); });
$state = new stdClass; $state->count = 0;
$rows[] = relative_capture('never/finally', function() use ($state) { return relative_never_finally($state); });
$rows[] = ['finally-count', $state->count];
foreach (['RelativeBase' => ['lexical', 'receiver', 'nullable', 'factory', 'parentFactory'],
          'RelativeOne' => ['traitSelf', 'traitParent', 'traitStatic'], 'RelativeTwo' => ['traitSelf', 'selfAlias', 'traitParent']] as $class => $methods) {
    foreach ($methods as $method) {
        $reflection = new ReflectionMethod($class, $method);
        $type = $reflection->getReturnType();
        $params = [];
        foreach ($reflection->getParameters() as $parameter) {
            $paramType = $parameter->getType();
            $params[] = $paramType ? [$paramType->getName(), $paramType->isBuiltin(), $paramType->allowsNull()] : null;
        }
        $rows[] = ['reflection/' . $class . '::' . $method, $type->getName(), $type->isBuiltin(), $type->allowsNull(), $params];
    }
}
$neverType = (new ReflectionFunction('relative_never'))->getReturnType();
$rows[] = ['reflection/never', $neverType->getName(), $neverType->isBuiltin(), $neverType->allowsNull()];
function relative_gen_generator(): Generator { yield 1; return 2; }
function relative_gen_iterator(): Iterator { yield 1; return 2; }
function relative_gen_traversable(): Traversable { yield 1; return 2; }
function relative_gen_iterable(): iterable { yield 1; return 2; }
function relative_gen_object(): object { yield 1; return 2; }
function relative_gen_mixed(): mixed { yield 1; return 2; }
function relative_gen_nullable(): ?Generator { yield 1; return 2; }
function relative_gen_empty(): Generator { if (false) yield 1; }
foreach (['generator', 'iterator', 'traversable', 'iterable', 'object', 'mixed', 'nullable', 'empty'] as $name) {
    $generator = call_user_func('relative_gen_' . $name);
    $values = [];
    foreach ($generator as $value) $values[] = $value;
    $rows[] = ['generator/' . $name, get_class($generator), $values, $generator->getReturn()];
}
$neverArrow = fn(): never => 1;
$rows[] = relative_capture('never/arrow', $neverArrow);
if (getenv('DEVLINE_RELATIVE_ORACLE')) echo serialize($rows);
return $rows;
