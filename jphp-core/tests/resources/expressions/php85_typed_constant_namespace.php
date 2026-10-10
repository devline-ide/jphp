<?php
namespace TypedConstants;
use \ReflectionClassConstant as Reflect;
use TypedConstants\Value as Alias;
class Value{} class ParentClass{}
define('NAMED_VALUE',new Value);
trait Types {const ?self SELF_VALUE=null;const ?parent PARENT_VALUE=null;}
class Consumer extends ParentClass {
    use Types;
    const ?Alias NAMED=NAMED_VALUE;
    const ?self OWN=null;
    const ?parent PARENT=null;
    const ?static LATE=null;
}
$rows=[];
foreach(['NAMED','OWN','PARENT','LATE','SELF_VALUE','PARENT_VALUE'] as $name) {
    $r=new Reflect(Consumer::class,$name);$t=$r->getType();$v=$r->getValue();
    $rows[]=[$name,get_class($t),(string)$t,$t->getName(),$t->isBuiltin(),$t->allowsNull(),is_object($v)?get_class($v):$v];
}
return $rows;
