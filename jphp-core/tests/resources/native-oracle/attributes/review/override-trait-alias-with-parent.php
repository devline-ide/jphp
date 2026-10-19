<?php
require __DIR__."/helper.php";
class ParentClass { public int $x=1; public function method(){} public function alias(){} }
trait TraitClass { #[Override] public function method(){} }
class ChildClass extends ParentClass { use TraitClass {method as alias;} #[Override] public int $x=2; }
emit('metadata',[attribute_view(new ReflectionMethod('ChildClass','method')),attribute_view(new ReflectionMethod('ChildClass','alias')),attribute_view(new ReflectionProperty('ChildClass','x'))]);emit('value',(new ChildClass)->x);
