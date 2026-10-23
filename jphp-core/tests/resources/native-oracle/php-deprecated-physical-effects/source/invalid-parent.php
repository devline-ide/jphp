<?php
error_reporting(E_ALL);
register_shutdown_function(function () {
    $last = error_get_last();
    echo 'shutdown=', json_encode([$last === null ? null : [$last['type'], $last['message']], class_exists('DepReviewParentConsumer', false)]), "\n";
});
set_error_handler(function ($number, $message) {
    echo 'warning=', json_encode([$number, $message, class_exists('DepReviewParentConsumer', false)]), "\n";
    return true;
});
require __DIR__ . '/invalid-parent/definitions.php';
echo 'before=', json_encode(class_exists('DepReviewParentConsumer', false)), "\n";
try {
    require __DIR__ . '/invalid-parent/subject.php';
    echo "require-returned\n";
} catch (Throwable $failure) {
    echo 'caught=', json_encode([get_class($failure), $failure->getMessage()]), "\n";
}
echo 'after=', json_encode(class_exists('DepReviewParentConsumer', false)), "\n";
