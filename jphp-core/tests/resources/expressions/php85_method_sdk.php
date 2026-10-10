<?php
$x='A';$class='php\\lib\\Char';$name='ord';
$rows=[];
$rows[]=php\lib\Char::ord($x);
$rows[]=$class::{$name}('B');
$rows[]=php\lib\Char::compare($x,$x='C');
$rows[]=$x;
$rows[]=php\lib\Str::pos('abcd','c');
$rows[]=php\lib\Str::sub('abcd',1,3);
$rows[]=(string)php\lib\Bin::of([65,66]);
return $rows;
