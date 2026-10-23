<?php
// Native output is pending. Only the current fixture's path is normalized.
$depEvents = []; $depThrow = false;
error_reporting(E_ALL);
set_error_handler(function($severity, $message) use (&$depEvents, &$depThrow) {
    $depEvents[] = ['warning', $severity, str_replace(__FILE__, '{fixture}', $message)];
    if ($depThrow) throw new RuntimeException('deprecation-handler');
    return true;
});
function dep_case($id, $work) {
    global $depEvents, $depThrow;
    $depEvents = []; $depThrow = false;
    try { $value = ['ok', $work()]; }
    catch (Throwable $error) { $value = ['throw', get_class($error), str_replace(__FILE__, '{fixture}', $error->getMessage())]; }
    echo $id, ':', json_encode([$value, $depEvents]), "\n";
}
#[Deprecated] function dep_plain(int $value = 7): int { global $depEvents; $depEvents[] = ['body', $value]; return $value; }
#[Deprecated(message: 'replacement', since: 'qa')] function dep_empty(): int { return 9; }
#[Deprecated('reference')] function &dep_reference() { global $depReference; return $depReference; }
#[Deprecated('generator')] function dep_generator() { global $depEvents; $depEvents[] = ['generator-body']; yield 5; }
class DepOwner {
    #[Deprecated('method', since: 'v2')] function value(int $value = 3): int { global $depEvents; $depEvents[] = ['method-body', $value]; return $value; }
    #[Deprecated('static')] static function stat(): int { return 11; }
}
class DepChild extends DepOwner { }
class DepOverride extends DepOwner { function value(int $value = 3): int { return 17; } }
dep_case('direct-result-used', function() { return dep_plain(); });
dep_case('direct-result-discarded', function() { dep_plain(8); return 'after'; });
dep_case('constant-return-not-folded-away', function() { return dep_empty() + dep_empty(); });
dep_case('dynamic-string', function() { $name = 'dep_plain'; return $name(4); });
dep_case('call-user-func', function() { return call_user_func('dep_plain', 4); });
dep_case('firstclass-acquisition-and-invocation', function() { global $depEvents; $f = dep_plain(...); $before = $depEvents; return [$before, $f(4)]; });
dep_case('reflection-invoke', function() { return (new ReflectionFunction('dep_plain'))->invoke(4); });
dep_case('reflection-get-closure', function() { return (new ReflectionFunction('dep_plain'))->getClosure()(4); });
dep_case('instance-static-inherited-overridden', function() {
    return [(new DepOwner)->value(), DepOwner::stat(), (new DepChild)->value(), (new DepOverride)->value()];
});
dep_case('method-dynamic-and-reflection', function() {
    $object = new DepOwner; $call = [$object, 'value'];
    return [$call(6), (new ReflectionMethod(DepOwner::class, 'value'))->invoke($object, 7)];
});
dep_case('reference-return', function() { global $depReference; $depReference = 1; $alias =& dep_reference(); $alias = 5; return $depReference; });
dep_case('generator-invocation-and-resume', function() { global $depEvents; $g = dep_generator(); $before = $depEvents; return [$before, $g->current()]; });
dep_case('anonymous-function-and-arrow', function() { $f = #[Deprecated('closure')] function() { return 2; }; $g = #[Deprecated('arrow')] fn() => 3; return [$f(), $g()]; });
dep_case('argument-effects-before-deprecation', function() { global $depEvents; return dep_plain((function() use (&$depEvents) { $depEvents[] = ['argument']; return 2; })()); });
dep_case('typeerror-order', function() { return dep_plain('not-numeric'); });
dep_case('missing-argument-order', function() { #[Deprecated] function dep_required(int $value) { return $value; } return dep_required(); });
dep_case('throwing-handler-prevents-body-and-publication', function() {
    global $depEvents, $depThrow; $depThrow = true; $published = 'old';
    try { $published = dep_plain((function() use (&$depEvents) { $depEvents[] = ['argument']; return 2; })()); }
    finally { $depThrow = false; $depEvents[] = ['published', $published]; }
    return $published;
});
dep_case('suppressed-call', function() { return @dep_plain(2); });
dep_case('throw-once-then-retry', function() {
    global $depThrow; $depThrow = true;
    try { dep_plain(1); } catch (RuntimeException $error) { }
    $depThrow = false; return dep_plain(2);
});
restore_error_handler();
