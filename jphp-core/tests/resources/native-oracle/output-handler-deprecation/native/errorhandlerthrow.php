<?php
$events=[];set_error_handler(function($code,$message)use(&$events){$events[]='err:'.$code;throw new Exception('deprecation-primary');});
ob_start(function($s){echo 'B';return '['.$s.']';});echo 'A';try{ob_end_flush();}catch(Throwable $e){$events[]='catch:'.$e->getMessage();}
restore_error_handler();echo '|'.implode(',',$events).'|Z';