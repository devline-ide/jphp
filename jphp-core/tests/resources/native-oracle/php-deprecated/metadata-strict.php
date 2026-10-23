<?php
declare(strict_types=1);
// Native output is pending; constructor binding must use the existing PHP argument machinery.
function dm_case($id, $work) {
    try { $value = ['ok', $work()]; }
    catch (Throwable $error) { $value = ['throw', get_class($error), $error->getMessage()]; }
    echo $id, ':', json_encode($value), "\n";
}
dm_case('builtin-shape', function() {
    $r = new ReflectionClass(Deprecated::class); $c = $r->getConstructor(); $p = [];
    foreach ($c->getParameters() as $parameter) $p[] = [$parameter->getName(), (string)$parameter->getType(), $parameter->isDefaultValueAvailable(), $parameter->getDefaultValue()];
    return [$r->isInternal(), $r->isFinal(), $p, $r->getAttributes(Attribute::class)[0]->newInstance()->flags];
});
dm_case('constructor-properties', function() {
    $a = new Deprecated(); $b = new Deprecated(since: 'arbitrary text', message: 'replacement');
    $r = new ReflectionClass(Deprecated::class); $p = [];
    foreach (['message', 'since'] as $name) { $property = $r->getProperty($name); $p[] = [$name, $property->isPublic(), $property->isReadOnly(), (string)$property->getType()]; }
    return [[$a->message, $a->since], [$b->message, $b->since], $p];
});
dm_case('constructor-weak-scalar-binding', function() { $a = new Deprecated(12, false); return [$a->message, $a->since]; });
dm_case('constructor-invalid-type', function() { return new Deprecated([]); });
dm_case('constructor-unknown-name', function() { return new Deprecated(unknown: 'x'); });
dm_case('readonly-reinitialization', function() { $a = new Deprecated('first', 'v'); $a->__construct('second', 'v2'); return $a->message; });
#[Deprecated('message', since: 'v')] function dm_old() { return 1; }
class DmOwner { #[Deprecated] function old() { return 2; } function fresh() { return 3; } }
dm_case('function-method-flags-and-attribute-instance', function() {
    $f = new ReflectionFunction('dm_old'); $m = new ReflectionMethod(DmOwner::class, 'old'); $fresh = new ReflectionMethod(DmOwner::class, 'fresh');
    $attribute = $f->getAttributes(Deprecated::class)[0]; $instance = $attribute->newInstance();
    return [$f->isDeprecated(), $m->isDeprecated(), $fresh->isDeprecated(), $attribute->getArguments(), $attribute->getTarget(), $attribute->isRepeated(), [$instance->message, $instance->since]];
});
