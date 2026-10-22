<?php
class MaskGeneral {
    public function a(){} protected function b(){} private function c(){} public static function d(){}
}
$reflection=new ReflectionClass(MaskGeneral::class);
function names($methods){$names=[];foreach($methods as $method)$names[]=$method->getName();sort($names);return $names;}
echo json_encode(['omitted',names($reflection->getMethods())],JSON_THROW_ON_ERROR),"\n";
foreach(['null'=>null,'zero'=>0,'public'=>ReflectionMethod::IS_PUBLIC,'protected'=>ReflectionMethod::IS_PROTECTED,'private'=>ReflectionMethod::IS_PRIVATE,'static'=>ReflectionMethod::IS_STATIC,'public-or-static'=>ReflectionMethod::IS_PUBLIC|ReflectionMethod::IS_STATIC] as $name=>$mask)
    echo json_encode([$name,names($reflection->getMethods($mask))],JSON_THROW_ON_ERROR),"\n";
