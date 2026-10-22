<?php
$events=[];error_reporting(E_ALL);set_error_handler(function($n,$m)use(&$events){$events[]=[$n,$m,error_reporting()];return true;});
function()use($discardMissing){};$events[]='after-discard';
@(function()use($quietDiscard){});$events[]=['after-quiet',error_reporting()];restore_error_handler();
$sentinel=new RuntimeException('discard');$same=false;$after=false;
set_error_handler(function($n,$m)use(&$events,$sentinel){$events[]=[$n,$m,error_reporting()];throw$sentinel;});
try{function()use($throwDiscard){};$after=true;}catch(RuntimeException$e){$same=$e===$sentinel;}
restore_error_handler();echo json_encode([$same,$after,$events]);
