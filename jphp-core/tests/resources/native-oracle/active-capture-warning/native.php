<?php
function active_capture_warning_probe($n, $s) {
    $GLOBALS['warningRows'][] = [$n, $s];
    return true;
}
$warningRows = [];
$prior = [];
$v = 7;
set_error_handler(function ($n, $s) use ($v) {
    $GLOBALS['activeMissing'] = $v;
    active_capture_warning_probe($n, $s);
    return true;
});
$closure = function () use ($prior, $activeMissing) { return $activeMissing; };
restore_error_handler();
echo json_encode([$closure(), $GLOBALS['activeMissing'], (new ReflectionFunction($closure))->getStaticVariables()['prior'], $warningRows]), "\n";
