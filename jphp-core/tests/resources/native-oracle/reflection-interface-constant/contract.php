<?php
interface ContractRootI {} interface ContractLeftI extends ContractRootI {} interface ContractRightI extends ContractRootI {} interface ContractDiamondI extends ContractLeftI,ContractRightI {}
class ContractBase implements ContractLeftI {public const BASE_PUBLIC=11;protected const BASE_PROTECTED=22;private const BASE_PRIVATE=33;final public const BASE_FINAL=44;}
class ContractChild extends ContractBase implements ContractDiamondI {public const OWN_PUBLIC=51;protected const OWN_PROTECTED=52;private const OWN_PRIVATE=53;}
class ContractNoParent {}
function row($id,$v){echo $id,':',json_encode($v,JSON_THROW_ON_ERROR),"\n";}
function constantInfo($c){return $c===false?false:[$c instanceof ReflectionClassConstant,$c->getName(),$c->getDeclaringClass()->getName(),$c->getValue(),$c->getModifiers()];}
$r=new ReflectionClass(ContractChild::class);
row('no-parent-false',(new ReflectionClass(ContractNoParent::class))->getParentClass()===false);
row('real-parent',$r->getParentClass()->getName());
row('singular-real-constant',[constantInfo($r->getReflectionConstant('OWN_PRIVATE')),constantInfo($r->getReflectionConstant('BASE_PUBLIC')),constantInfo($r->getReflectionConstant('BASE_FINAL'))]);
row('singular-missing-case-parent-private',[$r->getReflectionConstant('absent'),$r->getReflectionConstant('own_public'),$r->getReflectionConstant('BASE_PRIVATE')]);
$names=$r->getInterfaceNames();sort($names);row('interface-diamond-parent-set',[$names,count($names)===count(array_unique($names))]);
$map=[];foreach($r->getInterfaces()as $key=>$value)$map[$key]=[$value instanceof ReflectionClass,$value->getName(),$key===$value->getName()];ksort($map);row('interface-associative-mapping',$map);
foreach(['null'=>null,'zero'=>0,'public'=>1,'protected'=>2,'private'=>4,'final'=>32,'mixed'=>39]as $label=>$filter){
 $list=$r->getReflectionConstants($filter);$keys=array_keys($list);$shape=count($list)===0||$keys===range(0,count($list)-1);$values=[];foreach($list as $v)$values[$v->getName()]=constantInfo($v);ksort($values);row('reflection-filter-'.$label,[$shape,$values]);
 $values=$r->getConstants($filter);ksort($values);row('value-filter-'.$label,$values);
}
