<?php
require __DIR__ . "/helper.php";
foreach (['Attribute','AllowDynamicProperties','Override','SensitiveParameter','Deprecated','NoDiscard','DelayedTargetValidation','ReturnTypeWillChange'] as $n) { $r=new ReflectionClass($n); emit($n, [$r->isFinal(),attribute_view($r),array_map(static fn($a)=>$a->getArguments(),$r->getAttributes())]); }
$r=new ReflectionMethod('DateTimeImmutable','setDate'); emit('native-method',attribute_view($r)); (new DateTimeImmutable('2020-01-01'))->setDate(2021,1,1);
