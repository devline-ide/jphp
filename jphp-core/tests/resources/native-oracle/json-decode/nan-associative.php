<?php
function row($id,$throws){json_decode('{');$events=[];$failure=null;$value=null;set_error_handler(function($n,$s)use(&$events,$throws){$events[]=[$n,$s];if($throws)throw new Exception('nan-bool-handler');return true;});try{$value=json_decode('{}',NAN);}catch(Throwable $e){$failure=[get_class($e),$e->getMessage()];}restore_error_handler();$error=json_last_error();$message=json_last_error_msg();echo json_encode([$id,base64_encode(serialize($value)),$error,$message,$events,$failure]),"\n";}
row('nan-associative',false);row('nan-associative-handler-throws',true);
