<?php
function emit($tag,$value){echo $tag.'='.bin2hex(serialize($value))."\n";}
set_error_handler(function($severity,$message){emit('warning',[$severity,$message]);return true;});
#[AllowDynamicProperties] class ADPParent {} class ADPChild extends ADPParent {} class ADPPlain {}
class ADPMagic {public function __set($name,$value){emit('magic',[$name,$value]);}}
foreach([new ADPParent,new ADPChild,new ADPPlain,new stdClass,new ADPMagic] as $object){$object->extra=1;emit('class',get_class($object));}
$object=new ADPChild;$object->v=2;unset($object->v);$object->v=3;emit('inherit',[$object->v,property_exists($object,'v')]);
$restored=unserialize('O:8:"ADPChild":1:{s:1:"x";i:9;}');emit('restore',[$restored->x,get_class($restored)]);
$r=new ReflectionClass('AllowDynamicProperties');$a=$r->getAttributes()[0];emit('native',[$r->isFinal(),$a->getName(),$a->getArguments(),$r->getConstructor()->getNumberOfParameters(),get_class($a->newInstance())]);
try{new AllowDynamicProperties(1);}catch(Throwable $e){emit('constructor',[get_class($e),$e->getMessage()]);}
#[AllowDynamicProperties('unused')] class ADPUnusedArgument {} $object=new ADPUnusedArgument;$object->x=8;emit('unused',$object->x);
try{(new ReflectionClass('ADPUnusedArgument'))->getAttributes()[0]->newInstance();}catch(Throwable $e){emit('reflect-extra',[get_class($e),$e->getMessage()]);}
