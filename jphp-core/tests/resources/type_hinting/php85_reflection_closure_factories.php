<?php
function reflected_fn() { return 'function'; }
class ReflectedOwner { public $id; function __construct($id) {$this->id=$id;} function value() {return $this->id;} static function stat() {return 'static';} function makeFunction() {return (new ReflectionFunction('reflected_fn'))->getClosure();} }
class ReflectedChild extends ReflectedOwner {}
class ReflectedForeign {}
set_error_handler(function($n,$m){echo "warning:".$m."\n";return true;});
$a=new ReflectedOwner('A'); $b=new ReflectedOwner('B'); $child=new ReflectedChild('C');
$r=new ReflectionFunction('reflected_fn'); $f=$r->getClosure(); $f2=$r->getClosure();
echo 'function-distinct:',(int)($f!==$f2),'|', $f(),"\n";
foreach ([$f,$a->makeFunction()] as $c) { $rf=new ReflectionFunction($c);echo 'function-binding:',json_encode([$rf->getClosureThis()===null,$rf->getClosureScopeClass()===null,$rf->getClosureCalledClass()===null,$rf->isStatic()]),"\n"; }
$rm=new ReflectionMethod(ReflectedOwner::class,'value');$ca=$rm->getClosure($a);$cb=$rm->getClosure($b);$cc=$rm->getClosure($child);
echo 'method-distinct:',(int)($ca!==$cb),'|',$ca(),'|',$cb(),'|',$cc(),"\n";
foreach ([$ca,$cc] as $c) { $rf=new ReflectionFunction($c); echo 'method-binding:',json_encode([$rf->getClosureThis()->id,$rf->getClosureScopeClass()->getName(),$rf->getClosureCalledClass()->getName(),$rf->isStatic()]),"\n"; }
$bound=$ca->bindTo($b); echo 'rebound:', $bound===null?'NULL':$bound(),"\n";
$foreign=$ca->bindTo(new ReflectedForeign);echo 'foreign:',(int)($foreign===null),"\n";
$unbound=$ca->bindTo(null);echo 'unbound:',(int)($unbound===null),"\n";
$scoped=$ca->bindTo($b,ReflectedForeign::class);echo 'rescope:',(int)($scoped===null),"\n";
$fb=$f->bindTo($a);echo 'function-bind:',(int)($fb===null),"\n";
$rs=new ReflectionMethod(ReflectedOwner::class,'stat');foreach ([$rs->getClosure(),$rs->getClosure($a),$rs->getClosure($child)] as $c) { $rf=new ReflectionFunction($c);echo 'static-binding:',json_encode([$c(),$rf->getClosureThis()===null,$rf->getClosureScopeClass()->getName(),$rf->getClosureCalledClass()->getName(),$rf->isStatic()]),"\n"; }

function &reflected_reference() {global $factoryRef; return $factoryRef;}
$factoryRef=1;$cr=(new ReflectionFunction('reflected_reference'))->getClosure();$rr=&$cr();$rr=7;echo 'function-ref:', $factoryRef,"\n";
