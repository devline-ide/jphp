--TEST--
Integer overflow promotes folded, scalar and dynamic operations to float
--FILE--
<?php
function maximum() { return 9223372036854775807; }
function minimum() { return -9223372036854775807 - 1; }
var_dump(is_float(9223372036854775807 + 1));
var_dump(is_float(maximum() + 1), is_float(1 + maximum()));
var_dump(is_float(minimum() - 1), is_float(0 - minimum()));
var_dump(is_float(maximum() * 2), is_float(2 * maximum()), is_float(minimum() * -1));
$max = maximum();
$max += 1;
var_dump(is_float($max));
$min = minimum();
$min -= 1;
var_dump(is_float($min));
$product = maximum();
$product *= 2;
var_dump(is_float($product));
$max = maximum();
$previous = $max++;
var_dump(is_int($previous), is_float($max));
$min = minimum();
--$min;
var_dump(is_float($min), is_float(-minimum()));
var_dump(is_int(maximum() + 0), is_int(minimum() * 1));
--EXPECT--
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
bool(true)
