<?php
$rows=[];
foreach([0,1,123,E_WARNING,E_ALL]as$old)foreach([null,0,1,81,123,E_WARNING,E_ALL]as$new)foreach([false,true]as$throws){
 error_reporting($old);$events=[];
 set_error_handler(function($code,$message)use($new,$throws,&$events){$events[]=['handler',$code,error_reporting()];if($new!==null)error_reporting($new);if($throws)throw new RuntimeException('handler');return true;});
 try{@(function()use(&$events){try{trigger_error('quiet',E_USER_WARNING);$events[]=['after-trigger',error_reporting()];}finally{$events[]=['finally',error_reporting()];}})();}catch(RuntimeException$e){$events[]=['caught',$e->getMessage(),error_reporting()];}
 restore_error_handler();$rows[]=[$old,$new,$throws,$events,error_reporting()];
}
echo json_encode($rows);
