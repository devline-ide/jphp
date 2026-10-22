<?php
$rows=[];
foreach([0,1,123,E_WARNING,E_ALL]as$old)foreach([null,0,1,4437,81,123,E_WARNING,E_ALL]as$new)foreach([false,true]as$throws){
 error_reporting($old);$inside=[];$caught=false;
 try{@(function()use($new,$throws,&$inside){$inside[]=error_reporting();try{if($new!==null)error_reporting($new);$inside[]=error_reporting();if($throws)throw new RuntimeException('same');return 19;}finally{$inside[]=error_reporting();}})();}catch(RuntimeException$e){$caught=$e->getMessage()==='same';}
 $rows[]=[$old,$new,$throws,$inside,$caught,error_reporting()];
}
echo json_encode($rows);
