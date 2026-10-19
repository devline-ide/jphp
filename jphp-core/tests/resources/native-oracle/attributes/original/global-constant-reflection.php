<?php
require __DIR__ . "/helper.php";
#[Attribute(Attribute::TARGET_CONSTANT)] class A {} #[A] const G='value'; define('D', 'defined');
foreach (['G','D'] as $name) { $r=new ReflectionConstant($name); emit($name, [$r->getName(),$r->getValue(),$r->getNamespaceName(),$r->isDeprecated(),attribute_view($r)]); }
