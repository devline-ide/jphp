<?php
declare(strict_types=1);
$empty=["x"=>1];unset($empty["x"]);
$list=["x"=>1];$list[]="a";$list[]="b";unset($list["x"]);
$holey=[0=>"a",2=>"b"];
$reordered=[1=>"b",0=>"a"];
foreach(["empty"=>$empty,"list"=>$list,"holey"=>$holey,"reordered"=>$reordered]as$name=>$value)echo$name,":",json_encode($value),":",json_encode($value,JSON_FORCE_OBJECT),":",(int)array_is_list($value),"\n";
