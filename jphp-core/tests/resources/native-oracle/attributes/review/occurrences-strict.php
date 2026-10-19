<?php
declare(strict_types=1);
require_once __DIR__.'/ctor-weak.php'; require_once __DIR__.'/ctor-strict.php';
#[WeakCtor('12')] function strictOccurrenceWeakCtor() {}
#[StrictCtor('12')] function strictOccurrenceStrictCtor() {}
