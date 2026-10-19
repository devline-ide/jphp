<?php
function &yield_reference_cell() { $cell = 11; yield $cell; echo 'cell-after:', $cell, "\n"; return 'finished'; }
function yield_value_cell() { $cell = 21; yield $cell; echo 'value-after:', $cell, "\n"; return 'by-value'; }
function &yield_reference_twice() { $cell = 31; yield $cell; echo 'twice-between:', $cell, "\n"; yield $cell; echo 'twice-after:', $cell, "\n"; return 'twice'; }
function &yield_reference_argument($cell) { yield $cell; echo 'argument-after:', $cell, "\n"; return 'argument'; }
function &yield_reference_skipped($run) { $cell = 51; if ($run) { yield $cell; } return 'skipped-'.$cell; }
$g = yield_reference_cell(); foreach ($g as &$slot) { $slot = 99; } unset($slot); echo 'cell-return:', $g->getReturn(), "\n";
$g = yield_value_cell(); foreach ($g as $slot) { $slot = 99; } echo 'value-return:', $g->getReturn(), "\n";
$g = yield_reference_twice(); $round = 0; foreach ($g as &$slot) { echo 'twice-seen:', $slot, "\n"; $slot = ++$round == 1 ? 88 : 89; } unset($slot); echo 'twice-return:', $g->getReturn(), "\n";
$input = 41; $g = yield_reference_argument($input); foreach ($g as &$slot) { $slot = 77; } unset($slot); echo 'argument-input:', $input, ':', $g->getReturn(), "\n";
$g = yield_reference_skipped(false); foreach ($g as &$slot) { $slot = 99; } unset($slot); echo 'skipped-return:', $g->getReturn(), "\n";
function &yield_reference_array($cell) { yield $cell; echo 'array-after:', $cell[0], "\n"; return 'array'; }
$capture = 61;
$valueCapture = function &() use ($capture) { yield $capture; echo 'value-capture-after:', $capture, "\n"; return 'value-capture'; };
$g = $valueCapture(); foreach ($g as &$slot) { $slot = 86; } unset($slot); echo 'value-capture-input:', $capture, ':', $g->getReturn(), "\n";
$referenceCapture = function &() use (&$capture) { yield $capture; echo 'reference-capture-after:', $capture, "\n"; return 'reference-capture'; };
$g = $referenceCapture(); foreach ($g as &$slot) { $slot = 87; } unset($slot); echo 'reference-capture-input:', $capture, ':', $g->getReturn(), "\n";
$original = 71; $alias =& $original; $g = yield_reference_argument($alias); foreach ($g as &$slot) { $slot = 88; } unset($slot); echo 'alias-input:', $original, ':', $alias, ':', $g->getReturn(), "\n";
$originalArray = [81]; $g = yield_reference_array($originalArray); foreach ($g as &$slot) { $slot[0] = 89; } unset($slot); echo 'array-input:', $originalArray[0], ':', $g->getReturn(), "\n";
