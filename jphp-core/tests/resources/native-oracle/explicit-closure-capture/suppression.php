<?php
$events=[];error_reporting(E_ALL);
set_error_handler(function($type,$message)use(&$events){$events[]=[$type,$message,error_reporting()];return true;});
$f=@(function()use($quietMissing){return$quietMissing;});$value=$f();$after=error_reporting();restore_error_handler();
$sentinel=new RuntimeException('quiet');$same=false;
set_error_handler(function($type,$message)use(&$events,$sentinel){$events[]=[$type,$message,error_reporting()];throw$sentinel;});
try{@(function()use($quietThrow){return$quietThrow;});}catch(RuntimeException$e){$same=$e===$sentinel;}
restore_error_handler();echo json_encode([$value,$after,$same,error_reporting(),$events]);
