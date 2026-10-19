<?php
function emit($tag,$value){echo $tag.'='.bin2hex(serialize($value))."\n";}
#[DelayedTargetValidation,AllowDynamicProperties] function ADPDelayedFunction() {}
#[DelayedTargetValidation,AllowDynamicProperties] readonly class ADPDelayedReadonly {}
#[DelayedTargetValidation,AllowDynamicProperties] trait ADPDelayedTrait {}
foreach([new ReflectionFunction('ADPDelayedFunction'),new ReflectionClass('ADPDelayedReadonly'),new ReflectionClass('ADPDelayedTrait')] as $r){
$a=$r->getAttributes('AllowDynamicProperties')[0];try{$a->newInstance();}catch(Throwable $e){emit('lazy',[get_class($e),$e->getMessage()]);}}
$r=new ReflectionClass('DelayedTargetValidation');emit('delayed-native',[$r->isFinal(),$r->getAttributes()[0]->getArguments(),$r->getConstructor()===null]);
emit('delayed-arity',get_class(new DelayedTargetValidation(1)));
