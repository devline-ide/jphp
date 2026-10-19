<?php
$out = [];
$out['empty'] = [ob_get_clean(), ob_get_level()];
ob_start(); echo 'outer'; ob_start(); echo 'inner';
$out['nested'] = [ob_get_clean(), ob_get_level(), ob_get_clean(), ob_get_level()];
$events = [];
$handler = function($text, $phase) use (&$events) { $events[] = [$text, $phase]; return 'converted'; };
ob_start($handler); echo 'first';
$out['get'] = [ob_get_clean(), ob_get_level(), $events];
$events = [];
ob_start($handler); echo 'discard'; ob_clean(); echo 'keep';
$out['repeat'] = [ob_get_clean(), ob_get_level(), $events];
$events = [];
ob_start($handler); echo 'end';
$out['end'] = [ob_end_clean(), ob_get_level(), $events];
$events = [];
ob_start($handler);
$out['empty-handler'] = [ob_get_clean(), ob_get_level(), $events];
$throw = true;
ob_start(function($text, $phase) use (&$throw) { if ($throw) { $throw = false; throw new Exception('handler-failed'); } return $text; });
echo 'failed';
try { ob_get_clean(); } catch (Throwable $e) { $out['throw'] = [$e::class, $e->getMessage(), ob_get_level()]; }
while (ob_get_level() > 0) ob_end_clean();
echo json_encode($out, JSON_THROW_ON_ERROR), "\n";
