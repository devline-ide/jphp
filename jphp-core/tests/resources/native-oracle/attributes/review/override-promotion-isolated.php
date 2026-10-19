<?php
require __DIR__."/helper.php";
class ParentClass { public int $x=1; }
class ChildClass extends ParentClass { public function __construct(#[Override] public int $x=2){} }
emit('value',(new ChildClass)->x);
foreach([new ReflectionParameter(['ChildClass','__construct'],'x'),new ReflectionProperty('ChildClass','x')] as $r) {emit('metadata',attribute_view($r));foreach($r->getAttributes() as $a)attempt('instance-'.$a->getTarget(),fn()=>get_class($a->newInstance()));}
