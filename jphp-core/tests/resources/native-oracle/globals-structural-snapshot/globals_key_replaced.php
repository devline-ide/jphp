<?php
$base=1;$GLOBALS['GLOBALS']=123;$copy=$GLOBALS;array_key_exists('missing',$GLOBALS);set_error_handler(function(){ $GLOBALS['fresh']=17;$GLOBALS['base']=3;return true;});trigger_error('x',E_USER_WARNING);restore_error_handler();echo json_encode([$copy['GLOBALS'],$GLOBALS['GLOBALS'],$copy['base'],$base,$GLOBALS['fresh'],array_key_exists('fresh',$copy)]);
