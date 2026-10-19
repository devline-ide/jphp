<?php
declare(strict_types=1);
class H{private private(set) int$x=2;} $r=new ReflectionProperty(H::class,"x");echo $r->getModifiers(),":",(int)$r->isFinal(),":",(int)$r->isPrivateSet(),"\n";
