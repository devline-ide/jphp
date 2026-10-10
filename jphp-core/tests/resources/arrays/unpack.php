--TEST--
Array unpacking preserves PHP 8 keys, references, copy-on-write and evaluation order
--FILE--
<?php
var_dump([0, ...[1, 2], ...[], 3] === [0, 1, 2, 3]);
$source = [7 => 'a', 'x' => 1, -4 => 'b'];
var_dump([0, ...$source, 'x' => 2, ...['x' => 3], 4] === [0, 'a', 'x' => 3, 'b', 4]);
var_dump(array(...[1, 2], 3) === [1, 2, 3]);
$source = [[1], new stdClass()];
$copy = [...$source];
$copy[0][0] = 2;
var_dump($source[0][0] === 1, $copy[1] === $source[1]);
$value = 1;
$source = [&$value];
$copy = [...$source];
$copy[0] = 2;
var_dump($value === 2, $source[0] === 2);
$source = [1];
$copy = [...$source];
$copy[0] = 2;
var_dump($source[0] === 1);
$value = 1;
$source = ['x' => &$value];
$copy = [...$source, ...['x' => 2]];
$copy['x'] = 3;
var_dump($value === 1);
class ArrayEntries implements IteratorAggregate {
    function getIterator() { yield 5 => 10; yield 'x' => 20; }
}
var_dump([...new ArrayEntries()] === [10, 'x' => 20]);
function arraySource($value) { echo $value; return [$value]; }
$copy = [...arraySource('a'), ...arraySource('b')];
echo "\n";
var_dump($copy === ['a', 'b']);
function entries() { yield 10 => 'a'; yield 'x' => 1; yield 'x' => 2; yield '7' => 'b'; }
var_dump([...entries()] === ['a', 'x' => 2, 'b']);
function invalidKey() { yield [] => 1; }
try { $copy = [...invalidKey()]; echo "missed\n"; }
catch (Error $error) { echo "invalid key\n"; }
foreach ([1, null, new stdClass()] as $value) {
    try { $copy = [...$value]; echo "missed\n"; }
    catch (Error $error) { echo "invalid source\n"; }
}
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
ab
bool(true)
bool(true)
invalid key
invalid source
invalid source
invalid source
