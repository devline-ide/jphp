<?php
set_error_handler(function($level, $message, $file, $line) {
    echo 'W|', $level, '|', $message, '|', basename($file), '|', $line, "\n";
    return true;
});
#[NoDiscard]
function marked($value) {
    echo 'B|', $value, "\n";
    return $value;
}
function relay($dispatcher) {
    $dispatcher('marked', 1);
    $used = $dispatcher('marked', 2);
    (void)$dispatcher('marked', 3);
    echo 'R|', $used, "\n";
}
function relay_array($dispatcher) {
    $dispatcher('marked', [4]);
    $used = $dispatcher('marked', [5]);
    (void)$dispatcher('marked', [6]);
    echo 'R|', $used, "\n";
}
relay('call_user_func');
relay_array('call_user_func_array');
relay(call_user_func(...));
relay_array(call_user_func_array(...));
restore_error_handler();
