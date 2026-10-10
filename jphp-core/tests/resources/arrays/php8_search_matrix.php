<?php
class SearchString { public function __toString() { return 'abc'; } }
$object = new stdClass(); $object->n = 0;
$values = [null, false, true, 0, 1, -1, 1.0, -0.0, INF, -INF, NAN,
    '', '0', '1', '01', '1.0', ' 1 ', 'abc', '1abc', 'ёж', [], [0], ['abc'],
    ['n' => 0], ['n' => 'abc'], $object, clone $object, new SearchString()];
$output = '';
foreach ($values as $i => $needle) {
    foreach ($values as $j => $value) {
        foreach ([false, true] as $strict) {
            $haystack = [7 => $value, 'second' => $value];
            $output .= $i . ',' . $j . ',' . (int)$strict . ':' .
                serialize([in_array($needle, $haystack, $strict),
                    array_search($needle, $haystack, $strict), array_keys($haystack, $needle, $strict)]) . "\n";
        }
    }
    $output .= 'first,' . $i . ':' . serialize(array_search($needle, $values)) . "\n";
}
return $output;
