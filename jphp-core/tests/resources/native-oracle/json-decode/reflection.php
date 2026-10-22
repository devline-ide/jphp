<?php
$r=new ReflectionFunction('json_decode');$params=[];foreach($r->getParameters() as$p){$params[]=[$p->getName(),(string)$p->getType(),$p->isOptional(),$p->isDefaultValueAvailable()?$p->getDefaultValue():null];}echo json_encode([$r->getName(),$r->getNumberOfRequiredParameters(),$r->getNumberOfParameters(),(string)$r->getReturnType(),$params]),"\n";
