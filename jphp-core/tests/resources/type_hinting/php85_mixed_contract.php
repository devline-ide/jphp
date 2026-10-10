<?php
function type_identity(mixed $value): mixed { return $value; }
function type_missing(): mixed {}
function type_missing_nullable(): ?int {}
function type_missing_dynamic(mixed $value): mixed { if ($value) return null; }
function type_explicit_null(): mixed { return null; }
function type_undefined_variable(mixed $ignored): mixed { return $missing; }
function type_void(): void {}
class TypeReturns {
    function absent(): mixed {}
    function explicit(): mixed { return null; }
}
function capture_type($call) {
    try { return ['value', $call()]; }
    catch (Throwable $error) { return ['error', get_class($error)]; }
}
set_error_handler(function () { return true; });
$rows = [];
foreach ([null, false, true, 0, 2.5, '', 's', [3]] as $value) {
    $rows[] = [gettype($value), type_identity($value) === $value];
}
$object = new stdClass;
$closure = function () { return 1; };
$rows[] = ['object', type_identity($object) === $object];
$rows[] = ['closure', type_identity($closure) === $closure];
$resource = fopen('php://memory', 'r+');
$rows[] = ['resource', type_identity($resource) === $resource];
fclose($resource);
$returnObject = new TypeReturns;
$rows[] = capture_type(function () { return type_missing(); });
$rows[] = capture_type(function () { return type_missing_nullable(); });
$rows[] = capture_type(function () { return type_missing_dynamic(false); });
$rows[] = capture_type(function () { return type_missing_dynamic(true); });
$rows[] = capture_type(function () { return type_explicit_null(); });
$rows[] = capture_type(function () { return type_undefined_variable(1); });
$rows[] = capture_type(function () { return type_void(); });
$rows[] = capture_type(function () use ($returnObject) { return $returnObject->absent(); });
$rows[] = capture_type(function () use ($returnObject) { return $returnObject->explicit(); });
$reflection = new ReflectionFunction('type_identity');
$parameter = $reflection->getParameters()[0]->getType();
$returnType = $reflection->getReturnType();
$rows[] = [$parameter->getName(), $parameter->allowsNull(), $parameter->isBuiltin(),
           $returnType->getName(), $returnType->allowsNull(), $returnType->isBuiltin()];
if (getenv('DEVLINE_TYPE_ORACLE')) echo serialize($rows);
return $rows;
