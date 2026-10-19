<?php
require __DIR__ . "/helper.php";
class C { #[DelayedTargetValidation, Override] public const K=1; }
$r=new ReflectionClassConstant('C','K'); emit('metadata', attribute_view($r)); foreach ($r->getAttributes() as $a) attempt($a->getName(), fn() => get_class($a->newInstance()));
