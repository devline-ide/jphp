<?php
interface PMI {} interface PMJ {}
class PMBase {}
trait PMTrait {public self $self; public parent $parent;}
class PMOwner extends PMBase {
 use PMTrait;
 public int $i; public ?int $nullable; public mixed $mixed; public null $nil;
 public int|string $number; public (PMI&PMJ)|string|false $dnf;
 public float $float=1; public array $array=[1]; public $plain;
 private bool $secret=false; final public string $final='x';
 public static float $static=2; public static ?int $staticEmpty;
}
function pm_type($t){if($t===null)return null;$v=[get_class($t),(string)$t,$t->allowsNull()];
 if($t instanceof ReflectionNamedType){$v[]=$t->getName();$v[]=$t->isBuiltin();}else{$v[]=[];foreach($t->getTypes() as $p){$v[3][]=pm_type($p);}}return $v;}
function pm_capture($label,$fn){try{return [$label,'ok',$fn()];}catch(Throwable $e){return [$label,get_class($e),$e->getMessage()];}}
$obj=new PMOwner;$rows=[];
foreach(['i','nullable','mixed','nil','number','dnf','float','array','plain','secret','final','static','staticEmpty','self','parent'] as $name){
 $p=new ReflectionProperty('PMOwner',$name);
 $rows[]=['metadata',$name,$p->hasType(),pm_type($p->getType()),$p->hasDefaultValue(),$p->getDefaultValue(),$p->isInitialized($obj),$p->isDefault(),$p->isFinal(),$p->getModifiers()];
 $rows[]=pm_capture('reflection-read-'.$name,function()use($p,$obj){return $p->getValue($p->isStatic()?null:$obj);});
}
$rows[]=['default-properties',(new ReflectionClass('PMOwner'))->getDefaultProperties()];
$rows[]=['class-vars',get_class_vars('PMOwner')];
$rows[]=['object-vars',get_object_vars($obj)];
$values=[];foreach($obj as $k=>$v){$values[$k]=$v;}$rows[]=['foreach',$values];
$rows[]=['serialized-initial',serialize($obj)];
$rows[]=pm_capture('initial-read',function()use($obj){return $obj->i;});
$rows[]=['initial-quiet',isset($obj->i),empty($obj->i),$obj->i??'missing'];
$obj->i=7;$rows[]=['initialized',$obj->i,(new ReflectionProperty('PMOwner','i'))->isInitialized($obj)];
unset($obj->i);$rows[]=['explicit-unset',(new ReflectionProperty('PMOwner','i'))->isInitialized($obj)];
$rows[]=pm_capture('unset-read',function()use($obj){return $obj->i;});
$rows[]=['serialized-unset',serialize($obj)];
$rows[]=['coalesce-parenthesized',($obj->i)??'missing'];
$rows[]=pm_capture('coalesce-arithmetic-left',function()use($obj){return 1+$obj->i??'missing';});
$rows[]=pm_capture('coalesce-arithmetic-right',function()use($obj){return $obj->i+1??'missing';});
$rows[]=pm_capture('coalesce-call-argument',function()use($obj){$fn=function($x){return new PMOwner;};return $fn($obj->i)->i??'missing';});
$rows[]=pm_capture('coalesce-field-name',function()use($obj){return $obj->{$obj->i}??'missing';});
$rows[]=pm_capture('coalesce-array-key',function()use($obj){$arr=[];return $arr[$obj->i]??'missing';});
$rows[]=pm_capture('coalesce-assignment',function()use($obj){$v=$obj->i??'missing';return $v;});
$rows[]=['coalesce-nested-field',$obj->i->value??'missing'];
$rows[]=['coalesce-nested-offset',$obj->i['value']??'missing'];
$effects=[];
class PMMagic {function __isset($p){global $effects;$effects[]='isset:'.$p;return true;}function __get($p){global $effects;$effects[]='get:'.$p;return new PMOwner;}}
$magic=new PMMagic;$rows[]=['coalesce-magic',$magic->other->i??'missing',$effects];
$rows[]=['modifier-constants',ReflectionProperty::IS_PUBLIC,ReflectionProperty::IS_PROTECTED,ReflectionProperty::IS_PRIVATE,ReflectionProperty::IS_STATIC,ReflectionProperty::IS_FINAL];
foreach(['IS_PUBLIC','IS_PROTECTED','IS_PRIVATE','IS_STATIC','IS_FINAL'] as $name){
 $rows[]=['modifier-lookup',$name,ReflectionProperty::{$name},constant('ReflectionProperty::'.$name),(new ReflectionClass('ReflectionProperty'))->getConstant($name),(new ReflectionClassConstant('ReflectionProperty',$name))->getValue()];
}
$rc=new ReflectionClass('PMOwner');
foreach([null,0,1,2,4,16,32,17,20,1024] as $filter){$names=[];foreach($rc->getProperties($filter) as $p){$names[]=$p->getName();}sort($names);$rows[]=['property-filter',$filter,$names];}
return $rows;
