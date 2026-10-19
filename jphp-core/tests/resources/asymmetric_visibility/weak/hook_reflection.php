<?php
class H{public protected(set) int$x=2{get=>$this->x;set=>$value;}}$r=new ReflectionProperty(H::class,"x");foreach($r->getHooks()as$n=>$m)echo$n,":",$m->getModifiers(),":",(int)$m->isPublic(),":",(int)$m->isProtected(),"\n";
