<?php
declare(strict_types=1);
function attempt($name,$f){try{$v=$f();echo $name,":ok:",json_encode($v),"\n";}catch(Throwable $e){echo $name,":",get_class($e),":",$e->getMessage(),"\n";}}
class H{function __construct(public private(set) int $x=2,public protected(set) int $y=3){}}$h=new H;foreach(["x","y"]as$n){$r=new ReflectionProperty(H::class,$n);echo $n,":",$r->getModifiers(),":",(int)$r->isPrivateSet(),":",(int)$r->isProtectedSet(),":",(int)$r->isFinal(),":",(int)$r->isPromoted(),"\n";$r->setValue($h,"7");echo "reflection:",$r->getValue($h),"\n";$r->setRawValue($h,"8");echo "raw:",$r->getRawValue($h),"\n";}echo "flags:",ReflectionProperty::IS_PRIVATE_SET,":",ReflectionProperty::IS_PROTECTED_SET,"\n";
