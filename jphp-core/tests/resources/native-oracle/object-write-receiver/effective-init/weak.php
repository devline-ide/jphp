<?php

interface IProbe {public int $p {get;}}
class IBack implements IProbe {public int $p;}
class IVirtual implements IProbe {public int $p {get=>1;}}
class VParent {public int $p {get=>1;}}
class BChild extends VParent {public int $p;}
class ProtectedV {protected int $p {get=>1;}}
class PublicB extends ProtectedV {public int $p;}
class PrivateV {private int $p {get=>1;}}
class ShadowB extends PrivateV {public int $p;}
class PrivateB {private int $p;function init(){ $this->p=7;}}
class ShadowV extends PrivateB {public int $p {get=>1;}}
function caught($fn){try{return ['ok',$fn()];}catch(Throwable $e){return [get_class($e),$e->getMessage()];}}
$rows=[];$ib=new IBack;$iv=new IVirtual;$bc=new BChild;$pb=new PublicB;$sb=new ShadowB;$sv=new ShadowV;
foreach([[IProbe::class,$ib],[IProbe::class,$iv],[VParent::class,$bc],[ProtectedV::class,$pb],[PrivateV::class,$sb],[PrivateB::class,$sv],[ShadowB::class,$sb],[ShadowV::class,$sv]] as [$decl,$object]){
 $r=new ReflectionProperty($decl,'p');$rows[]=['before',$decl,get_class($object),$r->getDeclaringClass()->getName(),$r->isVirtual(),$r->isInitialized($object)];
}
$ib->p=1;$bc->p=2;$pb->p=3;$sb->p=4;$sv->init();
foreach([[IProbe::class,$ib],[VParent::class,$bc],[ProtectedV::class,$pb],[PrivateV::class,$sb],[PrivateB::class,$sv],[ShadowB::class,$sb]] as [$decl,$object]){
 $r=new ReflectionProperty($decl,'p');$rows[]=['after',$decl,get_class($object),$r->isInitialized($object)];
}
$r=new ReflectionProperty(IProbe::class,'p');$rows[]=['validation',caught(fn()=> $r->isInitialized(new stdClass)),caught(fn()=> $r->isInitialized()),caught(fn()=> $r->isInitialized(null)),caught(fn()=> $r->isInitialized(1))];
echo json_encode($rows,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
