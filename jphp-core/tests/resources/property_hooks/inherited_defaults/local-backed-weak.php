<?php
class BaseDefault {public int $x {get=>1;}} class ChildDefault extends BaseDefault {public int $x=3 {get=>$this->x;}} $c=new ChildDefault;$r=new ReflectionProperty(ChildDefault::class,'x');echo ($r->isVirtual()?'virtual':'backed').':'.$r->getDefaultValue().':'.$r->getRawValue($c).':'.$c->x."\n";
