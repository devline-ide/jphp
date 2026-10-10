<?php
declare(strict_types=1);
$closure = static function() { return 1; };
$rows = [];
foreach ([1, 2.5, true, false, []] as $scope) {
    try { $value = $closure->bindTo(null, $scope); $rows[] = ['value', $value]; }
    catch (Throwable $error) { $rows[] = ['error', get_class($error)]; }
}
if (getenv('DEVLINE_RELATIVE_ORACLE')) echo serialize($rows);
return $rows;
