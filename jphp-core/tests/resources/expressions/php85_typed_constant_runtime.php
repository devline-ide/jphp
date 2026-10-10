<?php
function inspectType($type) {
    if ($type===null) return null;
    $row=[get_class($type),(string)$type,$type->allowsNull(),$type instanceof ReflectionType,method_exists($type,'getName'),method_exists($type,'isBuiltin')];
    if ($type instanceof ReflectionNamedType) { $row[]=$type->getName(); $row[]=$type->isBuiltin(); }
    else { $children=[]; foreach($type->getTypes() as $child) $children[]=inspectType($child); $row[]=$children; }
    return $row;
}
interface TI{} interface TJ{} class TC{} class Both extends TC implements TI,TJ{}
define('OBJ',new Both); define('BAD','wrong'); define('NUMBER',5);
class Types {
    const NONE=1;
    const int I=1,J=2;
    const float F=1;
    const int|float EXACT=1;
    const float|string PROMOTED=1;
    const mixed M=null;
    const null N=null;
    const true T=true;
    const false FALSE_VALUE=false;
    const ?int NULLABLE=null;
    const int|string|bool|float|array|null UNION=null;
    const TI&TJ INTERSECTION=OBJ;
    const TC|(TI&TJ)|null DNF=OBJ;
    const object OBJECT=OBJ;
    const iterable ITEMS=[1];
    const ?iterable NULL_ITEMS=null;
    const iterable|int EXPANDED=1;
    const ?static STATIC_NAME=null;
    const int DEFERRED=NUMBER;
    const int WRONG=BAD;
    const int RETRY=LATER;
    const int MISSING=MISSING_VALUE;
    const \Closure FN=static function(){return 9;};
}
trait RelativeTypes { const ?self SELF_VALUE=null; const ?parent PARENT_VALUE=null; }
class ParentType{} class Relative extends ParentType {use RelativeTypes;}
$rows=[];
foreach(['NONE','I','J','F','EXACT','PROMOTED','M','N','T','FALSE_VALUE','NULLABLE','UNION','INTERSECTION','DNF','OBJECT','ITEMS','NULL_ITEMS','EXPANDED','STATIC_NAME','DEFERRED','WRONG','RETRY','MISSING','FN'] as $name) {
    $r=new ReflectionClassConstant(Types::class,$name);
    $rows[]=[$name,$r->hasType(),inspectType($r->getType())];
    if ($name==='WRONG'||$name==='RETRY'||$name==='MISSING') {try {$r->getValue();}catch(Throwable $e){$rows[]=[get_class($e),$e->getMessage()];}continue;}
    $v=$r->getValue(); $rows[]=[$name,gettype($v),is_object($v)?get_class($v):$v];
}
define('LATER',11); $rows[]=Types::RETRY;
$rows[]=(Types::FN)();
$a=Types::ITEMS; $a[0]=9; $rows[]=[Types::ITEMS,$a];
foreach(['SELF_VALUE','PARENT_VALUE'] as $name)$rows[]=inspectType((new ReflectionClassConstant(Relative::class,$name))->getType());
trait UnionTrait {const int|string X=1;}
class UnionConsumer {const string|int X=1;use UnionTrait;}
trait IntersectionTrait {const TI&TJ X=OBJ;}
class IntersectionConsumer {const TJ&TI X=OBJ;use IntersectionTrait;}
trait DnfTrait {const (TI&TJ)|null X=OBJ;}
class DnfConsumer {const null|(TJ&TI) X=OBJ;use DnfTrait;}
trait IterableTrait {const iterable X=[];}
class IterableConsumer {const Traversable|array X=[];use IterableTrait;}
class IterableBase {const iterable X=[];}
class IterableChild extends IterableBase {const Traversable|array X=[];}
class ExpandedBase {const Traversable|array X=[];}
class ExpandedChild extends ExpandedBase {const iterable X=[];}
foreach(['UnionConsumer','IntersectionConsumer','DnfConsumer','IterableConsumer','IterableChild','ExpandedChild'] as $class) {
    $r=new ReflectionClassConstant($class,'X'); $v=$r->getValue();
    $rows[]=[$class,inspectType($r->getType()),is_object($v)?get_class($v):$v];
}
foreach(['ReflectionNamedType','ReflectionUnionType','ReflectionIntersectionType'] as $class) {
    $rows[]=[$class,method_exists($class,'__construct')];
    foreach(['__toString','allowsNull','getName','isBuiltin','getTypes'] as $method) {
        $object=new $class;
        if (!method_exists($object,$method)) continue;
        try {$object->$method();$rows[]='unexpected';} catch(Throwable $e){$rows[]=[$class,$method,get_class($e),$e->getMessage()];}
    }
}
return $rows;
