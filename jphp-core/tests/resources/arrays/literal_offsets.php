--TEST--
Literal, folded and dynamic offset receivers produce valid bytecode
--FILE--
<?php
var_dump('abc'[0], ('a' . 'bc')[1], ('abc'[0] . 'bc')[2]);
var_dump([10, 20][1], ['x' => ['y' => 42]]['x']['y']);
$key = 2;
var_dump('abc'[$key]);
function offsetSource() { return 'abc'; }
var_dump(offsetSource()[1]);
function offsetKey() { echo "key\n"; return 0; }
'abc'[offsetKey()];
$array = [1, 2];
$reference = &$array[1];
$reference = 3;
var_dump($array[1], isset($array[1]), empty($array[1]));
unset($array[1]);
var_dump(isset($array[1]));
--EXPECT--
string(1) "a"
string(1) "b"
string(1) "c"
int(20)
int(42)
string(1) "c"
string(1) "b"
key
int(3)
bool(true)
bool(false)
bool(false)
