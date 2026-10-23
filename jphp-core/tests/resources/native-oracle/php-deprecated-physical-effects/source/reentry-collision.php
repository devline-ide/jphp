<?php
error_reporting(E_ALL);
register_shutdown_function(function () {
    $last = error_get_last();
    echo 'shutdown=', json_encode([$last === null ? null : [$last['type'], $last['message']], class_exists('DepReviewCollisionConsumer', false)]), "\n";
});
$entered = false;
set_error_handler(function ($number, $message) use (&$entered) {
    echo 'warning=', json_encode([$number, $message, class_exists('DepReviewCollisionConsumer', false)]), "\n";
    if (!$entered && strpos($message, 'Trait DepReviewCollisionTrait ') === 0) {
        $entered = true;
        echo "reentry-before\n";
        require __DIR__ . '/reentry-collision/reentry-definition.php';
        echo 'reentry-after=', json_encode(class_exists('DepReviewCollisionConsumer', false)), "\n";
    }
    return true;
});
require __DIR__ . '/reentry-collision/definitions.php';
echo 'before=', json_encode(class_exists('DepReviewCollisionConsumer', false)), "\n";
try {
    require __DIR__ . '/reentry-collision/subject.php';
    echo "require-returned\n";
} catch (Throwable $failure) {
    echo 'caught=', json_encode([get_class($failure), $failure->getMessage()]), "\n";
}
echo 'after=', json_encode(class_exists('DepReviewCollisionConsumer', false)), "\n";
