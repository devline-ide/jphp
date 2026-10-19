<?php
function emit($tag, $value) { echo $tag, '=', bin2hex(serialize($value)), "\n"; }
function attempt($tag, $fn) {
    try { emit($tag, $fn()); } catch (Throwable $e) { emit($tag . '-error', [get_class($e), $e->getMessage()]); }
}
function attribute_view($r) {
    return array_map(static function($a) { return [$a->getName(), $a->getTarget(), $a->isRepeated()]; }, $r->getAttributes());
}
set_error_handler(static function($severity, $message, $file, $line) {
    fwrite(STDERR, 'handled=' . bin2hex(serialize([$severity, $message, $file, $line])) . "\n");
    return true;
});
