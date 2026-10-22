<?php
$GLOBALS[5]='five';$copy=$GLOBALS;echo json_encode([array_key_exists(5,$copy),array_key_exists('5',$copy),$copy[5],array_keys(array_filter($copy,function($v){return $v==='five';}))]);
