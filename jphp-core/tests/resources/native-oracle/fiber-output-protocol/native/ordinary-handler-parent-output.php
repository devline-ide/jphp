<?php
$errors=[];set_error_handler(function($code,$message)use(&$errors){$errors[]=$code;return true;});
$f=new Fiber(function(){ob_start(function($s){Fiber::suspend('park');return '['.$s.']';});echo 'A';ob_end_flush();});
$r=$f->start();echo 'B';$f->resume();restore_error_handler();echo '|'.$r.'|errors='.implode(',',$errors).'|Z';