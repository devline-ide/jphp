<?php
$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=[$n,$m];return true;});
$x=11;$factory=fn()=>function()use($x){return$x;};$x=19;$firstClosure=$factory();$first=$firstClosure();
$y=21;$nested=fn()=>fn()=>function()use($y){return$y;};$y=22;$middle=$nested();$last=$middle();$second=$last();
function argumentFactory($value=31){$f=fn()=>function()use($value){return$value;};$value=39;$made=$f();return$made();}
restore_error_handler();echo json_encode([$first,$second,argumentFactory(),$events]);
