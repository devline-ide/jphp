<?php
require __DIR__.'/common.php';
row('interfaces',function(){$r=new ReflectionClass('ArrayObject');$v=$r->getInterfaceNames();sort($v);return [$r->isInternal(),$r->getParentClass(),$v,is_subclass_of('ArrayObject','ArrayIterator')];});
row('signatures',function(){$r=new ReflectionClass('ArrayObject');$result=[];foreach($r->getMethods() as $m){$args=[];foreach($m->getParameters() as $p)$args[]=[$p->getName(),$p->hasType(),(string)$p->getType(),$p->allowsNull(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];$result[$m->getName()]=[$args,$m->hasReturnType(),$m->hasTentativeReturnType()?(string)$m->getTentativeReturnType():null];}ksort($result);return $result;});
row('typed-constants',function(){$r=new ReflectionClass('ArrayObject');$a=[];foreach(['STD_PROP_LIST','ARRAY_AS_PROPS'] as $name){$c=$r->getReflectionConstant($name);$a[]=[$name,$c->getValue(),$c->isPublic(),$c->hasType(),(string)$c->getType()];}return $a;});
