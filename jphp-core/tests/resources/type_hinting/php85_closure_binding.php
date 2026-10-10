<?php
class BindingRoot {}
class BindingBase extends BindingRoot {
    private $secret = 'base';
    function closure() {
        return function() { return [self::class, parent::class, static::class, __CLASS__, get_class($this), $this->secret]; };
    }
    function relative() { return function(self $value): self { return $value; }; }
    function unusedThis() { return function() { return 1; }; }
    function propertyClosure() {
        return function($op, $value = null) {
            if ($op === 'set') $this->secret = $value;
            if ($op === 'ref') { $reference = &$this->secret; $reference = $value; }
            if ($op === 'isset') return isset($this->secret);
            return $this->secret;
        };
    }
    static function staticClosure() { return static function() { return [self::class, parent::class, static::class, __CLASS__]; }; }
    static function staticThis() { return static function() { return isset($this); }; }
    static function staticArrow() { return static fn($value): static => $value; }
}
class BindingChild extends BindingBase { private $secret = 'child'; }
class BindingOther {}
function binding_metadata($label, $closure) {
    if ($closure === null) return [$label, null];
    $reflection = new ReflectionFunction($closure);
    $scope = $reflection->getClosureScopeClass();
    $called = $reflection->getClosureCalledClass();
    $self = $reflection->getClosureThis();
    return [$label, $scope ? $scope->getName() : null, $called ? $called->getName() : null,
            $self ? get_class($self) : null, $reflection->isStatic()];
}
function binding_capture($label, $call) {
    try { return [$label, 'value', $call()]; }
    catch (Throwable $error) { return [$label, 'error', get_class($error)]; }
}
$base = new BindingBase; $child = new BindingChild; $other = new BindingOther;
$original = $child->closure();
$rows = [binding_metadata('original', $original)];
foreach (['default' => $original->bindTo($base), 'base' => $original->bindTo($base, 'BindingBase'),
          'child' => $original->bindTo($child, 'BindingChild'), 'object-scope' => $original->bindTo($child, $child),
          'static-bind' => Closure::bind($original, $child, 'BindingChild')] as $label => $closure) {
    $rows[] = binding_metadata($label, $closure);
    $rows[] = binding_capture('invoke/' . $label, $closure);
}
$rows[] = binding_capture('call/base', function() use ($original, $base) { return $original->call($base); });
$rows[] = binding_capture('call/child', function() use ($original, $child) { return $original->call($child); });
$rows[] = binding_metadata('original-after-call', $original);
$rows[] = binding_capture('original-after-call', $original);
$rows[] = binding_metadata('unused-this', $child->unusedThis());
$rows[] = ['fresh-identity', $child->unusedThis() === $child->unusedThis()];
$relative = $child->relative();
$rebound = $relative->bindTo($child, 'BindingChild');
foreach (['original' => $relative, 'rebound' => $rebound] as $label => $closure) {
    $reflection = new ReflectionFunction($closure);
    $rows[] = ['type/' . $label, $reflection->getParameters()[0]->getType()->getName(), $reflection->getReturnType()->getName()];
    foreach ([$base, $child, $other] as $value) {
        $rows[] = binding_capture('relative/' . $label . '/' . get_class($value), function() use ($closure, $value) {
            return get_class($closure($value));
        });
    }
}
$static = BindingChild::staticClosure();
$rows[] = binding_metadata('static/original', $static);
$rows[] = binding_capture('static/invoke', $static);
foreach (['base' => $static->bindTo(null, 'BindingBase'), 'child' => $static->bindTo(null, 'BindingChild'),
          'default' => $static->bindTo(null), 'null' => $static->bindTo(null, null)] as $label => $closure) {
    $rows[] = binding_metadata('static/' . $label, $closure);
    $rows[] = binding_capture('static/invoke/' . $label, $closure);
}
$rows[] = binding_capture('static/this', BindingChild::staticThis());
$warnings = [];
set_error_handler(function($severity, $message) use (&$warnings) { $warnings[] = $severity; return true; });
$rows[] = binding_metadata('static/cannot-bind-this', $static->bindTo($child));
$rows[] = ['static/cannot-call-this', $static->call($child)];
$rows[] = binding_metadata('cannot-unbind-this', $original->bindTo(null));
$rows[] = binding_metadata('internal-scope', $static->bindTo(null, 'stdClass'));
$rows[] = binding_metadata('unknown-scope', $static->bindTo(null, 'MissingBindingScope'));
$rows[] = ['internal-call', $original->call(new stdClass)];
$rows[] = binding_capture('invalid-scope-type', function() use ($static) { return $static->bindTo(null, 1); });
$rows[] = binding_capture('invalid-binding-object', function() use ($original) { return $original->bindTo(1); });
$rows[] = binding_capture('invalid-call-object', function() use ($original) { return $original->call(null); });
restore_error_handler();
$rows[] = ['warnings', $warnings];
$global = function(self $value): self { return $value; };
$rows[] = binding_metadata('global/original', $global);
$globalBound = $global->bindTo($child, 'BindingBase');
$rows[] = binding_metadata('global/bound', $globalBound);
$rows[] = binding_capture('global/bound', function() use ($globalBound, $base) { return get_class($globalBound($base)); });
$rows[] = binding_metadata('global/default-scope', $global->bindTo($child));
$rows[] = binding_metadata('global/null-scope', $global->bindTo($child, null));
$rows[] = binding_metadata('global/rebind-default', $global->bindTo($child)->bindTo($base));
$arrow = BindingChild::staticArrow();
$rows[] = binding_metadata('arrow/static', $arrow);
$rows[] = binding_capture('arrow/child', function() use ($arrow, $child) { return get_class($arrow($child)); });
$rows[] = binding_capture('arrow/base', function() use ($arrow, $base) { return get_class($arrow($base)); });
$property = $child->propertyClosure();
$childProperty = $property->bindTo($child, 'BindingChild');
foreach (['read', 'set', 'ref', 'isset', 'read'] as $op) {
    $rows[] = ['property/base/' . $op, $property($op, $op . '/base')];
    $rows[] = ['property/child/' . $op, $childProperty($op, $op . '/child')];
}
if (getenv('DEVLINE_RELATIVE_ORACLE')) echo serialize($rows);
return $rows;
