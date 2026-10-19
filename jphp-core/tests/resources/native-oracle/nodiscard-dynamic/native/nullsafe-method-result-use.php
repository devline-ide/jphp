<?php
set_error_handler(function($level, $message, $file, $line) {
    echo 'W|', $level, '|', $message, '|', basename($file), '|', $line, "\n";
    return true;
});
#[NoDiscard]
function marked_argument($value) {
    echo 'A|', $value, "\n";
    return $value;
}
class NullableHolder {
    #[NoDiscard]
    public function marked($value) {
        echo 'M|', $value, "\n";
        return $value;
    }
}
$holder = new NullableHolder();
$holder?->marked('discarded');
$used = $holder?->marked('used');
(void)$holder?->marked('void');
echo 'R|', $used, "\n";
$none = null;
$none?->marked(marked_argument('skipped-discarded'));
$noneUsed = $none?->marked(marked_argument('skipped-used'));
(void)$none?->marked(marked_argument('skipped-void'));
echo is_null($noneUsed) ? "N|null\n" : "BAD\n";
restore_error_handler();
