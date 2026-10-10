<?php
function de_effect($value='effect'){global $deEvents;$deEvents[]=$value;return $value;}
function de_capture($name,$fn){global $deEvents;$deEvents=[];$result=null;try{$result=$fn();}catch(Throwable $error){$deEvents[]=[get_class($error),$error->getMessage()];}return [$name,$result,$deEvents];}
class DeBox {public $p=3;public static $s=4;const C=5;public function __get($name){de_effect('get:'.$name);return 6;}}
class DeArray implements ArrayAccess {public function offsetExists($key): bool{return true;}public function offsetGet($key): mixed{return de_effect('offset:'.$key);}public function offsetSet($key,$value): void{}public function offsetUnset($key): void{}}
class DeCtor {public function __construct($value){de_effect('constructor:'.$value);}}
$rows=[];
$rows[]=de_capture("variable",function(){$x=3;$x;return $x;});
$rows[]=de_capture("array-cell",function(){$a=[3];$a[de_effect(0)];return $a;});
$rows[]=de_capture("nested-array-literal",function(){[de_effect(1),[de_effect(2)]];return 3;});
$rows[]=de_capture("object-field",function(){$o=new DeBox;$o->p;return $o->p;});
$rows[]=de_capture("object-dynamic-field",function(){$o=new DeBox;$o->{de_effect("p")};return $o->p;});
$rows[]=de_capture("magic-field",function(){$o=new DeBox;$o->missing;return $o->p;});
$rows[]=de_capture("arrayaccess-cell",function(){$o=new DeArray;$o[de_effect("key")];return 3;});
$rows[]=de_capture("static-field",function(){DeBox::$s;return DeBox::$s;});
$rows[]=de_capture("class-constant",function(){DeBox::C;return DeBox::C;});
$rows[]=de_capture("static-dynamic-field",function(){$p="s";DeBox::${de_effect($p)};return DeBox::$s;});
$rows[]=de_capture("constructor",function(){new DeCtor(de_effect("arg"));return 3;});
$rows[]=de_capture("constructor-array-literal",function(){[new DeCtor(de_effect("arg")),de_effect("next")];return 3;});
$rows[]=de_capture("paren-magic-field",function(){$o=new DeBox;($o->missing);return 3;});
$rows[]=de_capture("paren-arrayaccess-cell",function(){$o=new DeArray;($o[de_effect("key")]);return 3;});
return $rows;
