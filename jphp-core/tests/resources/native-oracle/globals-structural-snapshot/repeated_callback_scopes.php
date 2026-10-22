<?php
array_key_exists('missing',$GLOBALS);set_error_handler(function(){ $GLOBALS['one']=17;return true;});trigger_error('x',E_USER_WARNING);restore_error_handler();array_key_exists('missing',$GLOBALS);set_error_handler(function(){ $GLOBALS['two']=18;return true;});trigger_error('x',E_USER_WARNING);restore_error_handler();echo json_encode([$GLOBALS['one'],$GLOBALS['two']]);
