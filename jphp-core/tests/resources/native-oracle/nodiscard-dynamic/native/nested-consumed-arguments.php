<?php
set_error_handler(function($level, $message, $file, $line) {
    echo 'W|', $level, '|', $message, '|', basename($file), '|', $line, "\n";
    return true;
});
#[NoDiscard]
function marked_value($value) {
    echo 'V|', $value, "\n";
    return $value;
}
function collect($value) {
    echo 'C|', $value, "\n";
}
class ArgumentHolder {
    #[NoDiscard]
    public function marked($value) {
        echo 'M|', $value, "\n";
        return $value;
    }
    public function take($value) {
        echo 'T|', $value, "\n";
    }
}
$holder = new ArgumentHolder();
collect(marked_value('nested'));
$holder->take(marked_value('method-argument'));
$holder->take($holder->marked('method-nested'));
collect($holder->marked('function-argument'));
$holder->marked(marked_value('discarded-outer'));
(void)$holder->marked(marked_value('void-outer'));
$used = $holder->marked(marked_value('used-outer'));
echo 'R|', $used, "\n";
restore_error_handler();
