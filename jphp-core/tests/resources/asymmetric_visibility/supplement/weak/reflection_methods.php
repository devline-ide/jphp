<?php
declare(strict_types=0);
class R {public function pub(){} protected static function pro(){} private function pri(){} final public function fin(){}}
abstract class A {abstract protected function abs();}
echo "constants:",ReflectionMethod::IS_PUBLIC,",",ReflectionMethod::IS_PROTECTED,",",ReflectionMethod::IS_PRIVATE,",",ReflectionMethod::IS_STATIC,",",ReflectionMethod::IS_FINAL,",",ReflectionMethod::IS_ABSTRACT,"\n";
foreach([[R::class,"pub"],[R::class,"pro"],[R::class,"pri"],[R::class,"fin"],[A::class,"abs"]] as $spec){$r=new ReflectionMethod($spec[0],$spec[1]);echo $spec[1],":",$r->getModifiers(),":",(int)$r->isStatic(),":",(int)$r->isPublic(),":",(int)$r->isProtected(),":",(int)$r->isPrivate(),"\n";}
class H {public protected(set) int $x=2 {get=>$this->x;set=>$value;}}
$r=new ReflectionProperty(H::class,"x");$h=new H;foreach($r->getHooks()as$n=>$m)echo"hook-",$n,":",$m->getModifiers(),":",(int)$m->isStatic(),":",(int)$m->isPublic(),"\n";
echo "invoke-get:",$r->getHooks()["get"]->invoke($h),"\n";$r->getHooks()["set"]->invoke($h,7);echo"invoke-set:",$h->x,"\n";
