<?php
declare(strict_types=1);
class BaseDefault {public int $x=1;} class ChildDefault extends BaseDefault {public int $x=3 {get=>42;set{echo "setter:$value\n";}}} $c=new ChildDefault;$r=new ReflectionProperty(ChildDefault::class,'x');echo ($r->isVirtual()?'virtual':'backed').':'.($r->hasDefaultValue()?'default':'none').':'.$r->getDefaultValue().':'.$r->getRawValue($c).':'.$c->x."\n";$c->x=9;echo $r->getRawValue($c).':'.(new BaseDefault)->x."\n";
