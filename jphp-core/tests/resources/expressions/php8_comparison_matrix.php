<?php
// Native oracle: PHP 8.3.6, -n, errors suppressed. Each row is i,j,==,!=,<,<=,>,>=,<=>.
class ComparableString { public $value; function __construct($value) { $this->value = $value; } function __toString() { return $this->value; } }
class OtherObject { public $x = 0; }
$values = [null, false, true, -1, 0, 1, 10, 100,
    9223372036854775807, -9223372036854775807 - 1,
    -0.0, 0.0, 1.0, 0.1, 1e10, 1e18, NAN, INF, -INF,
    '', '0', '00', ' 0 ', '01', '1', '1e1', '10foo', 'abc', '1e400', '2e400', 'INF', 'NAN', '😀', '￿',
    [], [0], ['abc'], ['x' => 1, 'y' => 2], ['y' => 2, 'x' => 1], [1 => 0], [2 => 0],
    new stdClass, (object)['x' => 0], (object)['x' => 'abc'], (object)['x' => '0'], (object)['x' => NAN],
    new ComparableString('0'), new ComparableString('abc'), new OtherObject];
$out = '';
foreach ($values as $i => $a) {
    foreach ($values as $j => $b) {
        $out .= $i . ',' . $j . ',' . (int)($a == $b) . ',' . (int)($a != $b) . ',' .
            (int)($a < $b) . ',' . (int)($a <= $b) . ',' . (int)($a > $b) . ',' .
            (int)($a >= $b) . ',' . ($a <=> $b) . "\n";
    }
}
return $out;
