<?php
ob_implicit_flush(true);
$f=new Fiber(function(){
    ob_start(function($s){Fiber::suspend('handler-park');return '['.$s.']';});
    echo 'A';ob_end_flush();
});
$r=$f->start();$parked=$f->isSuspended();if($parked)$f->resume();
echo '|park='.$r.'|suspended='.($parked?'yes':'no').'|Z';