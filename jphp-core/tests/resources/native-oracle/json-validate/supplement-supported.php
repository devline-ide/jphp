<?php
$json='[]';$events=[];set_error_handler(function($n,$s)use(&$json,&$events){$events[]=$n.':'.$s;$json='bogus';return true;});
$result=json_validate($json,512.5);$code=json_last_error();$message=json_last_error_msg();restore_error_handler();echo json_encode(['captured-send',$result,$code,$message,$json,$events]),"\n";
json_decode('{');$json='[]';set_error_handler(function($n,$s){throw new Exception('precision-handler');});$caught=null;try{json_validate($json,512.5);}catch(Throwable $e){$caught=$e->getMessage();}restore_error_handler();$code=json_last_error();$message=json_last_error_msg();echo json_encode(['throw-before-state',$caught,$code,$message]),"\n";
$json=str_repeat('[',2048).'0'.str_repeat(']',2048);$result=json_validate($json,2049);$code=json_last_error();$message=json_last_error_msg();echo json_encode(['deep-native-supported',$result,$code,$message,strlen($json)]),"\n";
