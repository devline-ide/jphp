<?php
function tc_capture($label,$fn){$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=[$n,$m];return true;});$result=null;try{$result=$fn();}catch(Throwable $e){$events[]=[get_class($e),$e->getMessage()];}restore_error_handler();return [$label,$result,$events];}
trait TcValues {
 /** trait public */ public const PUB=1;
 protected const PROT=2; private const PRIV=3;
 final public const FINAL=4;
 public const U=[1,[2]]; public const u=[3,[4]];
 public const SELF_NAME=self::class;
 public const SEED_VALUE=self::SEED;
 public static function read(){return [self::PUB,self::PROT,self::PRIV,self::FINAL,self::SEED_VALUE,self::SELF_NAME];}
}
trait TcNested {use TcValues;public const EXTRA=5;}
class TcParent {public const ROOT=10;}
class TcFirst extends TcParent {private const SEED="first";use TcNested;}
class TcChild extends TcFirst {}
class TcSecond {private const SEED="second";use TcValues;}
trait TcDynamicA {public const X=self::SEED;}
trait TcDynamicB {public const X=1;}
class TcOwn {private const SEED=1;public const X=self::SEED;use TcDynamicA,TcDynamicB;}
interface TcInterface {public const X=12;final public const Y=13;}
class TcImplement implements TcInterface {public const X=14;}
interface TcDerivedInterface extends TcInterface {public const X=15;}
class TcDerivedImplement implements TcDerivedInterface {}
$rows=[];
for($pass=0;$pass<2;$pass++)foreach(["TcFirst","TcChild","TcSecond"]as $c){
 $rows[]=tc_capture([$pass,$c,"read"],function()use($c){return $c::read();});
 foreach(["PUB","PROT","PRIV","FINAL","U","u","SELF_NAME","SEED_VALUE"]as $name){
  $rows[]=tc_capture([$pass,$c,$name,"direct"],function()use($c,$name){return $c::{$name};});
  $rows[]=tc_capture([$pass,$c,$name,"reflection"],function()use($c,$name){$r=new ReflectionClassConstant($c,$name);return [$r->getValue(),$r->getName(),$r->getDeclaringClass()->getName(),$r->getModifiers(),$r->isFinal(),$r->isPublic(),$r->isProtected(),$r->isPrivate(),$r->getDocComment()];});
 }
 $rows[]=tc_capture([$pass,$c,"cow"],function()use($c){$a=$c::U;$a[1][0]=99;$r=new ReflectionClassConstant($c,"U");$b=$r->getValue();$b[0]=88;return [$a,$b,$c::U,$r->getValue(),$c::u];});
}
foreach(["TcValues","TcNested"]as $c)foreach(["PUB","PROT","PRIV","FINAL","U","SELF_NAME","MISSING"]as $name)
 $rows[]=tc_capture([$c,$name,"trait"],function()use($c,$name){return $c::{$name};});
foreach(["TcValues","TcNested"]as $c)foreach(["PUB","PROT","PRIV","FINAL","U"]as $name)
 $rows[]=tc_capture([$c,$name,"trait-reflection"],function()use($c,$name){$r=new ReflectionClassConstant($c,$name);return [$r->getValue(),$r->getDeclaringClass()->getName(),$r->getModifiers(),$r->isFinal()];});
$rows[]=tc_capture("compatible-own",function(){return TcOwn::X;});
foreach(["TcInterface","TcImplement","TcDerivedInterface","TcDerivedImplement"]as $c)
 $rows[]=tc_capture([$c,"interface"],function()use($c){$x=new ReflectionClassConstant($c,"X");$y=new ReflectionClassConstant($c,"Y");return [$c::X,$c::Y,$x->getDeclaringClass()->getName(),$x->isFinal(),$y->getDeclaringClass()->getName(),$y->getModifiers(),ReflectionClassConstant::IS_FINAL];});
foreach(["TcChild",new TcChild,"tcfirst","TcMissing"]as $c)foreach(["PUB","PRIV","MISSING"]as $name)
 $rows[]=tc_capture([is_object($c)?"object":$c,$name,"constructor"],function()use($c,$name){$r=new ReflectionClassConstant($c,$name);return [$r->name,$r->class,$r->getName(),$r->getDeclaringClass()->getName(),$r->getValue()];});
$rows[]=tc_capture("modifier-bits",function(){return [ReflectionClassConstant::IS_PUBLIC,ReflectionClassConstant::IS_PROTECTED,ReflectionClassConstant::IS_PRIVATE,ReflectionClassConstant::IS_FINAL];});
return $rows;
