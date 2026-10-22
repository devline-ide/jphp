<?php
$existing=1;array_key_exists('missing',$GLOBALS);set_error_handler(function(){ $GLOBALS['existing']=9;return true;});trigger_error('x',E_USER_WARNING);restore_error_handler();echo json_encode([$existing,$GLOBALS['existing']]);
