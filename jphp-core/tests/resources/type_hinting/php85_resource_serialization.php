<?php
$r = fopen('php://memory', 'r+');
$rows = [serialize($r), serialize([$r, $r]), serialize([&$r, &$r]),
    serialize([$r, &$r, &$r]), serialize([$r, [$r], [$r]])];
fclose($r);
$rows[] = serialize($r);
return $rows;
