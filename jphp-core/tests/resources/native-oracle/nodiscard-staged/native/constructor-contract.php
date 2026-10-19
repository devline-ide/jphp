<?php
require __DIR__.'/helper.php';
$r=new ReflectionClass(NoDiscard::class);$p=$r->getProperty('message');$c=$r->getConstructor();
emit('metadata',[$r->isFinal(),$r->getAttributes()[0]->getArguments(),$p->isPublic(),$p->isReadOnly(),(string)$p->getType(),$c->getNumberOfParameters(),$c->getNumberOfRequiredParameters()]);
$a=new NoDiscard;emit('default',$a->message);$b=new NoDiscard('x');emit('message',$b->message);$d=clone $b;emit('clone',[$d!==$b,$d->message]);
attempt('reinit',function()use($b){$b->__construct('changed');});attempt('write',function()use($b){$b->message='changed';});
attempt('null',fn()=>(new NoDiscard(null))->message);attempt('weak-int',fn()=>(new NoDiscard(17))->message);attempt('array',fn()=>(new NoDiscard([]))->message);
emit('serialize',unserialize(serialize($b))->message);
#[NoDiscard(new LazyMessage)] function lazy(){emit('lazy-body',true);return 1;}class LazyMessage{function __construct(){emit('argument-constructor',true);}}
emit('lazy-used',lazy());attempt('lazy-discard',function(){lazy();});
