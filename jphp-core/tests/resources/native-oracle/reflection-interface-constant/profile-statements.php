<?php
$p=(new ReflectionClass(ProfileNoParent::class))->getParentClass();
$r=new ReflectionClass(ProfileChild::class);$constants=$r->getReflectionConstants();$keys=array_keys($constants);
$interfaces=(new ReflectionClass(ProfileBase::class))->getInterfaces();$interfaceKeys=array_keys($interfaces);
echo ($p===null?'null':($p===false?'false':'parent')),'|',(count($keys)&&is_int($keys[0])?'list':'assoc'),'|',count($r->getConstants(0)),'|',(count($interfaceKeys)&&is_string($interfaceKeys[0])?'assoc':'list'),"\n";
