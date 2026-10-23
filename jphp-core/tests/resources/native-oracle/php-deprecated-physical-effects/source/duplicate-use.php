<?php
#[Deprecated('each declared use')]
trait DepR4RepeatTrait {}
set_error_handler(function ($number, $message) { echo 'warning=', json_encode([$number, $message, class_exists('DepR4RepeatUser', false)]), "\n"; return true; });
class DepR4RepeatUser { use DepR4RepeatTrait, DepR4RepeatTrait; }
echo 'after=', json_encode(class_exists('DepR4RepeatUser', false)), "\n";
