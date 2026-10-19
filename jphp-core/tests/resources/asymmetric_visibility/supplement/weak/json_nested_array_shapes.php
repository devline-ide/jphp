<?php
declare(strict_types=0);
$empty=["x"=>1];unset($empty["x"]);
$list=["x"=>1];$list[]="a";$list[]="b";unset($list["x"]);
$nested=["empty"=>$empty,"list"=>$list,"holey"=>[0=>"a",2=>"b"]];
echo json_encode($nested),"\n",json_encode($nested,JSON_FORCE_OBJECT),"\n";
class NestedJSON implements JsonSerializable {public function jsonSerialize():mixed {$empty=["x"=>1];unset($empty["x"]);$list=["x"=>1];$list[]=7;$list[]=8;unset($list["x"]);return["empty"=>$empty,"list"=>$list];}}
echo json_encode(new NestedJSON),"\n";
