<?php
// Snapshot scalar values independently of serialize() reference numbering.
function sort_values_snapshot($array) {
    $snapshot = [];
    foreach ($array as $key => $value) $snapshot[$key] = (int)$value;
    return $snapshot;
}
$output = '';
$values = [0, 'abc', 1, '1'];
foreach ($values as $i => $a) {
    foreach ($values as $j => $b) {
        if ($i === $j) continue;
        foreach ($values as $k => $c) {
            if ($k === $i || $k === $j) continue;
            foreach ($values as $l => $d) {
                if ($l === $i || $l === $j || $l === $k) continue;
                foreach (['sort', 'rsort', 'asort', 'arsort'] as $function) {
                    $array = ['a' => $a, 'b' => $b, 'c' => $c, 'd' => $d];
                    $copy = $array;
                    $ok = $function($array, SORT_REGULAR);
                    $output .= $function . ':' . serialize([$ok, $array, $copy]) . "\n";
                }
            }
        }
    }
}
foreach (['ksort', 'krsort'] as $function) {
    $array = ['abc' => 1, 0 => 2, 1 => 3, 'x' => 4]; $copy = $array;
    $output .= $function . ':' . serialize([$function($array), $array, $copy]) . "\n";
}
foreach (['usort', 'uasort', 'uksort'] as $function) {
    $array = ['a' => 3, 'b' => 1, 'c' => 2]; $copy = $array;
    $count = 0;
    $function($array, function($a, $b) use (&$array, &$count) {
        $count = count($array);
        return $a <=> $b;
    });
    $output .= $function . '-visible:' . serialize([$count, $array, $copy]) . "\n";
    $array = ['a' => 3, 'b' => 1, 'c' => 2];
    try { $function($array, function($a, $b) { throw new Exception('stop'); }); }
    catch (Exception $e) { $output .= $function . '-throw:' . serialize($array) . "\n"; }
}
foreach (['sort', 'rsort', 'asort', 'arsort', 'ksort', 'krsort'] as $function) {
    $x = 3; $array = ['a' => &$x, 'b' => 1]; $copy = $array;
    $function($array); $x = 7;
    $output .= $function . '-reference:' . serialize([sort_values_snapshot($array), sort_values_snapshot($copy), $x]) . "\n";
    $array = ['a' => [3], 'b' => [1]]; $copy = $array;
    $function($array); $key = array_keys($array)[0]; $array[$key][0] = 9;
    $output .= $function . '-nested:' . serialize([$array, $copy]) . "\n";
}
return $output;
