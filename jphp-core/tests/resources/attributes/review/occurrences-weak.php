<?php
require_once __DIR__.'/ctor-weak.php'; require_once __DIR__.'/ctor-strict.php';
#[WeakCtor('12')] function weakOccurrenceWeakCtor() {}
#[StrictCtor('12')] function weakOccurrenceStrictCtor() {}
