<?php
require __DIR__.'/helper.php';
function arg(){emit('arg',true);return 1;}#[NoDiscard] function typed(int $v){emit('body',$v);return 2;}
attempt('bad-argument',function(){typed([]);});
set_error_handler(function($s,$m){emit('throwing-warning',[$s,$m]);throw new Exception('handler');});
attempt('direct',function(){typed(arg());return 'completed';});attempt('cuf',function(){call_user_func('typed',arg());return 'completed';});attempt('cufa',function(){call_user_func_array('typed',[arg()]);return 'completed';});
