<?php
$f=new ReflectionFunction('json_encode');$parameters=[];
foreach($f->getParameters()as$p)$parameters[]=[$p->getName(),(string)$p->getType(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];
echo json_encode([$f->getName(),$f->getNumberOfRequiredParameters(),$f->getNumberOfParameters(),(string)$f->getReturnType(),$parameters]),"\n";
