<?php
require __DIR__."/helper.php";
#[Attribute] class A {} #[A] function sample() {}
$a=(new ReflectionFunction('sample'))->getAttributes()[0];$r=new ReflectionProperty('ReflectionAttribute','name');emit('property',[$r->isReadOnly(),(string)$r->getType(),$r->getModifiers()]);
attempt('unset-name',function()use($a){unset($a->name);return isset($a->name);});
attempt('reference-name',function()use($a){$ref=&$a->name;return $ref;});
emit('name-after',[$a->name,$a->getName()]);
