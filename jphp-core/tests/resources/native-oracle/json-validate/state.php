<?php
function emit($id,$result){$code=json_last_error();$msg=json_last_error_msg();echo json_encode([$id,$result,$code,$msg]),"\n";}
emit('initial-none',null);
json_validate('[}');emit('validate-failure',null);
json_decode('null');emit('decode-clears-validate',null);
json_decode('{');json_validate('null');emit('validate-clears-decode',null);
json_decode('{');try{json_encode(new class implements JsonSerializable{function jsonSerialize():mixed{throw new Exception('sentinel');}},JSON_THROW_ON_ERROR);}catch(Throwable $e){}emit('encode-callback-throw-preserves-state',null);
json_decode('{');set_error_handler(function($n,$s){throw new Exception('null-handler');});try{json_validate(null);}catch(Throwable $e){$message=$e->getMessage();}restore_error_handler();emit('null-handler-before-state',$message);
