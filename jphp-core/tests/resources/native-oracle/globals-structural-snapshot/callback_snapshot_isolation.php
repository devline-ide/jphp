<?php
$old=1;$copy=$GLOBALS;array_key_exists('missing',$GLOBALS);set_error_handler(function(){ $GLOBALS['fresh']=17;$GLOBALS['old']=4;return true;});trigger_error('x',E_USER_WARNING);restore_error_handler();echo json_encode([$GLOBALS['fresh'],$old,$copy['old'],array_key_exists('fresh',$copy)]);
