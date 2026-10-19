<?php
declare(strict_types=1);
class BaseDefault {public int $x=1;} $c=new class extends BaseDefault {public int $x=3 {get=>42;}}; $r=new ReflectionProperty($c,'x');echo ($r->isVirtual()?'virtual':'backed').':'.$r->getDefaultValue().':'.$r->getRawValue($c).':'.$c->x."\n";
