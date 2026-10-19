<?php
declare(strict_types=1);
require __DIR__."/helper.php";
require __DIR__.'/occurrences-weak.php'; require __DIR__.'/occurrences-strict.php';
foreach(['weakOccurrenceWeakCtor','weakOccurrenceStrictCtor','strictOccurrenceWeakCtor','strictOccurrenceStrictCtor'] as $name) { $a=(new ReflectionFunction($name))->getAttributes()[0];emit($name.'-arguments',$a->getArguments());attempt($name.'-instance',fn()=>(array)$a->newInstance()); }
