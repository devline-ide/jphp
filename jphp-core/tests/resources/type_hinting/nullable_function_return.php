--TEST--
Nullable function return metadata survives compilation and module dump
--FILE--
<?php
function nullableInt(?int $value): ?int { return $value; }
function nullableString(?string $value): ?string { echo ''; return $value; }
function nullableObject(?stdClass $value): ?stdClass { return $value; }
function nullableConstant(): ?int { return null; }
function &nullableReference(?int &$value): ?int { return $value; }
class NullableMethods {
    function value(?int $value): ?int { return $value; }
}
$closure = function(?int $value): ?int { return $value; };
$value = null;
var_dump(nullableInt(null), nullableString(null), nullableObject(null), nullableConstant());
var_dump(nullableReference($value) === null, (new NullableMethods)->value(null), $closure(null));
var_dump(nullableInt(42), nullableString('ok'));
--EXPECT--
NULL
NULL
NULL
NULL
bool(true)
NULL
NULL
int(42)
string(2) "ok"
