<?php
declare(strict_types=1);
class GrandDefault {public int $x=1;} class BaseDefault extends GrandDefault {public int $x {get=>5;}} class ChildDefault extends BaseDefault {public int $x=3 {get=>42;}} $c=new ChildDefault;$r=new ReflectionProperty(ChildDefault::class,'x');echo ($r->isVirtual()?'virtual':'backed').':'.$r->getDefaultValue().':'.$r->getRawValue($c).':'.$c->x."\n";
