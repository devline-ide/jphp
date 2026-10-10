<?php
$before = count(get_included_files());
$counter = 0;
$first = include_once __DIR__ . '/php8_once_counter.inc.php';
$alias = require_once __DIR__ . '/./php8_once_counter.inc.php';
$repeat = include __DIR__ . '/php8_once_counter.inc.php';
$skip = include_once __DIR__ . '/php8_once_counter.inc.php';
$self = include_once __DIR__ . '/php8_once_self.inc.php';
$error = '';
try { include_once __DIR__ . '/php8_once_throw.inc.php'; }
catch (Exception $exception) { $error = $exception->getMessage(); }
$afterError = require_once __DIR__ . '/php8_once_throw.inc.php';
return serialize([$before, $first, $alias, $repeat, $skip, $self, $error, $afterError, $counter, count(get_included_files()) - $before]);
