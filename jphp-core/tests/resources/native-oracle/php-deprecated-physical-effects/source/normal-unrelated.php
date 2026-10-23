<?php
error_reporting(E_ALL);
echo 'normal-before=', json_encode([class_exists('DepR4Plain', false), class_exists('DepR4UnmarkedUser', false)]), "\n";
class DepR4Plain {}
trait DepR4UnmarkedTrait {}
class DepR4UnmarkedUser { use DepR4UnmarkedTrait; }
#[Deprecated('reserved name')]
trait DepR4MarkedTrait {}
function dep_r4_define_unrelated() { class DepR4DuringHandler {} }
set_error_handler(function ($number, $message) {
    echo 'handler-before=', json_encode([$number, $message, class_exists('DepR4MarkedUser', false), class_exists('DepR4DuringHandler', false), class_exists('DepR4Plain', false)]), "\n";
    dep_r4_define_unrelated();
    echo 'handler-after=', json_encode([class_exists('DepR4MarkedUser', false), class_exists('DepR4DuringHandler', false), class_exists('DepR4Plain', false)]), "\n";
    return true;
});
class DepR4MarkedUser { use DepR4MarkedTrait; }
echo 'after=', json_encode([class_exists('DepR4MarkedUser', false), class_exists('DepR4DuringHandler', false), class_exists('DepR4Plain', false), class_exists('DepR4UnmarkedUser', false)]), "\n";
