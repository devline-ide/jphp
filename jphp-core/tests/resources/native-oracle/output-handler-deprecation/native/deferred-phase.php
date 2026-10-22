<?php
$events=[];set_error_handler(function($code,$message)use(&$events){$events[]='error:'.$code.':'.(Fiber::getCurrent()?'F':'M');return true;});
$f=new Fiber(function()use(&$events){ob_start(function($s)use(&$events){$events[]='enter';Fiber::suspend('park');$events[]='resume';return '['.$s.']';});echo 'A';ob_end_flush();$events[]='handler-finished';});
$f->start();$events[]='start-returned';echo 'B';$events[]='after-B';$f->resume();$events[]='after-resume';restore_error_handler();echo '|'.implode(',',$events).'|';