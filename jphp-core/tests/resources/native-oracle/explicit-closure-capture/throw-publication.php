<?php
$events=[];$published='original';$sentinel=new RuntimeException('capture');$same=false;$argument=false;
function argument(){ $GLOBALS['argument']=true;return 1; }
set_error_handler(function($type,$message)use(&$events,$sentinel){$events[]=[$type,$message];throw$sentinel;});
try{$published=(function()use($throwMissing){return 7;})(argument());}catch(RuntimeException$e){$same=$e===$sentinel;}
restore_error_handler();echo json_encode([$published,$same,$argument,array_key_exists('throwMissing',$GLOBALS),$events]);
